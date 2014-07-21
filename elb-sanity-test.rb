#!/usr/bin/env ruby -w
#
# elb-sanity-test

class ElbHealthCheckTargetDescription
  def initialize(health_check_target)
    colon_pos = health_check_target.index(":")
    @protocol = health_check_target[0,colon_pos].downcase.to_sym
    stop_looking_pos=-1
    @url = nil
    if ["http", "https"].include?(@protocol)
      stop_looking_pos = health_check_target.index("/")-1
      @url = health_check_target[stop_looking_pos+2..-1]
    end
    @port = health_check_target[colon_pos+1..stop_looking_pos].to_i    
  end
  
  attr_reader :protocol, :port, :url
end


require 'rubygems'
require 'thor'
require 'aws-sdk'

class ElbSanityTest < Thor

  class_option :verbose, :type => :boolean, :aliases => '-v',
    :desc => "Show verbose output", :required => false
  class_option :debug, :type => :boolean, :aliases => '-d',
    :desc => "Show debug output. Includes verbose output.", :required => false
    
  method_option :aws_access_key_id, :type => :string, :required => false, 
    :desc => "The AwsAccessKeyId", :default => "AWS_ACCESS_KEY_ID environment variable"
  method_option :aws_secret_access_key, :type => :string, :required => false, 
    :desc => "The AwsSecretAccessKey", :default => "AWS_SECRET_ACCESS_KEY environment variable"
  method_option :region, :type => :array, :required => false, 
    :desc => "AWS region(s) to check. Defaults to all regions. Example: --regions=us-east-1 us-west-2 eu-west-1",
    :default => [ "<all>" ]
  method_option :name, :type => :array, :required => false,
    :desc => "Name of ELB(s) to check. If not specified, checks all ELBs in the specified region(s). Example: --name=elbName1 elbName2",
    :default => [ "<all>" ]
  desc "check", "Checks ELBs for sanity"
  def check

    @regions = []
    @names = []
    @elb_client = nil
    @ec2_client = nil
    aws_access_key_id = options[:aws_access_key_id]
    if aws_access_key_id == "AWS_ACCESS_KEY_ID environment variable"
      aws_access_key_id = ENV['AWS_ACCESS_KEY_ID']
    end
    aws_secret_access_key = options[:aws_secret_access_key]
    if aws_secret_access_key == "AWS_SECRET_ACCESS_KEY environment variable"
      aws_secret_access_key = ENV['AWS_SECRET_ACCESS_KEY']
    end
    if aws_access_key_id.nil? || aws_access_key_id.length==0 ||
      aws_secret_access_key.nil? || aws_secret_access_key.length==0
      abort("Specify both aws_access_key_id and aws_secret_access_key, either on the command line or via AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables")
    end
    AWS.config(:access_key_id => aws_access_key_id,
               :secret_access_key => aws_secret_access_key)

    if options[:region] && options[:region].size==1 && options[:region].first=="<all>"
      @regions = AWS::EC2.new.regions.map(&:name)
    else
      @regions = options[:region]
    end
    log("Running ELB sanity check in these regions: #{@regions.join(",")}")
    
    if options[:name] && options[:name].size>0 && options[:name].first!="<all>"
      @names.concat(options[:name])
    end
      
    @elb_client = AWS::ELB.new    
    lbs = @elb_client.load_balancers
    if @names.nil? || @names.size==0
      # get them from the API
      @names = lbs.map(&:name)
    end
    log("Checking these ELBs: #{@names.join(",")}")
    check_lbs(lbs)
    log("Done checking all specified ELBs")
  end
  
  default_task :check
  
  no_tasks do
  
    protected
  
    def say(str)
      puts str
    end
    
    def log(str)
      say(str) if options[:verbose] || options[:debug]
    end
    
    def debug(str)
      say(str) if options[:debug]
    end
    
    def error(name, location, message)
      say("ERROR: ELB #{name} in #{location}: #{message}")
    end

    def warn(name, location, message)
      say("WARNING: ELB #{name} in #{location}: #{message}")
    end
    
    def check_healthy_instances_in_all_enabled_zones(lbs)
      say("Checking that all enabled availability zones have healthy instances.")

      lbs.each do |lb|
        log("Checking ELB #{lb.name}")
        
        azs = lb.availability_zone_names
        az_to_instance_array_map = Hash[azs.map {|az| [az, []]}]
        instances = lb.instances
        instance_to_az_map = Hash[instances.map {|i| [i.instance_id, i.availability_zone]}]
        instances.health.each do |instance_health|
          if instance_health[:state].casecmp("InService")
            instance_id = instance_health[:instance].instance_id
            az_to_instance_array_map[instance_to_az_map[instance_id]].push(instance_id)
          end
        end
        debug("AZ map: #{az_to_instance_array_map.reduce(Hash.new(0)) { |h, (k, v)| h[k]=v.size; h }.inspect}")
        is_all_active_azs_equal=true
        last_size=-1
        az_to_instance_array_map.each do |az, instance_array|
          if instance_array.size==0
            error(lb.name, az[0..-2], "Availability zone #{az} is enabled but has no healthy instances in it.")
          end
          if is_all_active_azs_equal && last_size != instance_array.size && last_size != -1
            is_all_active_azs_equal=false
            next
          end
          last_size=instance_array.size
        end
        az_to_instance_array_map.delete_if do |az, instance_array|
          is_dead_az = !azs.include?(az)
          if is_dead_az
            warn(lb.name, az[0..-2], "Has #{instance_array.size} instances registered in availability zone #{az} but that zone is not enabled.")
          end
          is_dead_az
        end
        if !is_all_active_azs_equal
          warn(lb.name, azs.first[0..-2], "Uneven distribution of healthy instances across enabled availability zones: #{az_to_instance_array_map.reduce(Hash.new(0)) { |h, (k, v)| h[k]=v.size; h }.inspect}")
        end
        log("Done checking ELB #{lb.name}")
      end
    end
    
    def check_health_check_matches_listener(lbs)
      say("Checking for a health check with same instance protocol and instance port as a listener.")
      
      lbs.each do |lb|
        log("Checking ELB #{lb.name}")
        
        health_check = lb.health_check
        if health_check.nil?
          warn(lb.name, lb.availability_zone_names.first[0..-2], "No health check is configured")
          next
        end
        health_check_target = health_check[:target]
        if health_check_target.nil?
          warn(lb.name, lb.availability_zone_names.first[0..-2], "No health check is configured")
          next
        end
        health_check_desc = ElbHealthCheckTargetDescription.new(health_check_target)
        debug("Health check is protocol #{health_check_desc.protocol} port #{health_check_desc.port}")
        has_matching_listener = lb.listeners.any? do |listener|
          debug("a listener: Front: #{listener.protocol} port #{listener.port}, Back: #{listener.instance_protocol} port #{listener.instance_port}")
          health_check_desc.protocol==listener.instance_protocol && health_check_desc.port==listener.instance_port
        end
        if !has_matching_listener
          warn(lb.name, lb.availability_zone_names.first[0..-2], "No listener exists with instance protocol and instance port matching health check's procotol #{health_check_desc.protocol} and port #{health_check_desc.port}")
        end
        log("Done checking ELB #{lb.name}")
      end
    end
    
    def check_instance_ports_open_in_security_group(lbs)
      say("Checking that instance security groups allow traffic from all listeners.")
      
      lbs.each do |lb|
        log("Checking ELB #{lb.name}")
        
        if lb.instances.nil? || lb.instances.count==0
          warn(lb.name, lb.availability_zone_names.first[0..-2], "No instances assigned to this ELB")
        end
        required_instance_ports = lb.listeners.reduce([]) { |arr, listener| arr << listener.instance_port }
        required_instance_ports.uniq!
        debug("Required instance ports: #{required_instance_ports.inspect}")
        sg_descriptions = {}
        lb.instances.each do |instance|
          debug("Checking instance #{instance.id}")
          this_instance_remaining_required_ports = [].concat(required_instance_ports)
          instance.security_groups.each do |sg|
            descs = sg_descriptions[sg.id]
            if descs.nil?
              descs = sg.ingress_ip_permissions
              sg_descriptions[sg.id] = descs
            end
            debug("SG: #{sg.id} Name: #{sg.name}")
            descs.each do |desc|
              debug("Permission: #{desc.protocol} CIDRs: #{desc.ip_ranges.join(',')} Port range: #{desc.port_range}")
              debug("  Groups: #{desc.groups.reduce([]) { |a, g| a << "#{g.id}"; a }.join(',')}") if !desc.groups.empty? 
              if desc.protocol != :tcp
                debug("Protocol is not TCP")
                next
              end
              skip_cidr_check=false
              if !desc.groups.empty?
                if !desc.groups.any? { |g| g.id=="sg-843f59ed" } # the global "from ELB" security group
                  debug("Permission is to a non-ELB SG")
                  next
                else
                  debug("Permission is to ELB's SG")
                  skip_cidr_check=true
                end
              end
              if !skip_cidr_check
                if !desc.ip_ranges.include?("0.0.0.0/0")
                  debug("Permission is locked to a non-global IP address range")
                  next
                else
                  debug("Permission is to global IP address range")
                end
              end
              this_instance_remaining_required_ports.delete_if { |port| t=desc.port_range.include?(port); t && debug("Found opening for port #{port}"); t }
              break if this_instance_remaining_required_ports.empty?
            end # each permission description
            break if this_instance_remaining_required_ports.empty?
          end # each security group
          if !this_instance_remaining_required_ports.empty?
            error(lb.name, lb.availability_zone_names.first, "Instance #{instance.id} does not have these ports open to listen to ELB traffic: #{this_instance_remaining_required_ports.join(",")}")
          end
        end # each instance
        log("Done checking ELB #{lb.name}")
      end
    end
    
    def check_lbs(lbs)
      valid_lbs = []
      lbs.each do |lb|
        next if !@names.include?(lb.name)

        azs = lb.availability_zone_names
        if azs.nil? || azs.length==0
          warn(lb.name, "unknown region", "No availability zones configured")
          next
        end
        this_lb_region = azs.first[0..-2]
        next if !@regions.include?(this_lb_region)
        valid_lbs.push(lb)
      end
      check_instance_ports_open_in_security_group(valid_lbs)
      check_health_check_matches_listener(valid_lbs)
      check_healthy_instances_in_all_enabled_zones(valid_lbs)
    end

  end # no_task
end

ElbSanityTest.start

