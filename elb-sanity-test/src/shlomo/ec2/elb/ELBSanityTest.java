package shlomo.ec2.elb;

/**
 * Copyright 2009 Shlomo Swidler
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
import static org.junit.Assert.*;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.GroupDescription;
import com.xerox.amazonws.ec2.HealthCheck;
import com.xerox.amazonws.ec2.InstanceState;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.Listener;
import com.xerox.amazonws.ec2.LoadBalancing;
import com.xerox.amazonws.ec2.LoadBalancer;
import com.xerox.amazonws.ec2.LoadBalancingException;
import com.xerox.amazonws.ec2.ReservationDescription;
import com.xerox.amazonws.ec2.GroupDescription.IpPermission;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;

/**
 * Test ELBs for sanity.
 * Depends on the environment variable AWS_CREDENTIAL_FILE pointing to the 
 * file which contains this info:
 * AWSAccessKeyId=<Write your AWS access ID>
 * AWSSecretKey=<Write your AWS secret key>
 * This is the same file format supported by the ELB tools.
 * @author Shlomo Swidler
 */
public class ELBSanityTest {
	
	private static Jec2 Ec2 = null;
	private static LoadBalancing ELB = null;
	private static List<LoadBalancer> LoadBalancers = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		String credentialsFile = System.getenv("AWS_CREDENTIAL_FILE");
		if (credentialsFile == null) {
			throw new IllegalStateException("Define the AWS_CREDENTIAL_FILE " +
				"environment variable to point to the file containing " +
				"your AWS credentials. See the ELB Tools' README.TXT");
		}
		Properties credentialsProps = new Properties();
		BufferedInputStream is = new BufferedInputStream(
			new FileInputStream(credentialsFile));
		credentialsProps.load(is);
		is.close();
		String awsAccessId = credentialsProps.getProperty("AWSAccessKeyId");
		String awsSecretKey = credentialsProps.getProperty("AWSSecretKey");
		if (awsAccessId == null || awsAccessId.length()<1 ||
			awsSecretKey == null || awsSecretKey.length()<1) {
			throw new IllegalStateException("File pointed to by environment " +
				"variable AWS_CREDENTIAL_FILE (" + credentialsFile + 
				") does not define AWSAccessKeyId and " +
				"AWSAccessKeyId properties.");
		}
		Ec2 = new Jec2(awsAccessId, awsSecretKey, true);
		ELB = new LoadBalancing(awsAccessId, awsSecretKey, true);
		LoadBalancers = ELB.describeLoadBalancers();
		assertTrue("Account has at least one ELB", LoadBalancers != null && 
			LoadBalancers.size() > 0);
	}

	@Test
	public void testInstancePortsOpenInSecurityGroup() throws EC2Exception {
		System.out.println("Test: all instances have their Security Groups " +
			"defined to allow access to the ELB listener port");
		HashMap<String, GroupDescription> securityGroupName2GroupDescriptionMap
			= new HashMap<String, GroupDescription>();
		for (LoadBalancer lb : LoadBalancers) {
			System.out.println("Load Balancer: " + lb.getName());
			List<Listener> listeners = lb.getListeners();
			List<String> instances = lb.getInstances();
			assertTrue("ELB has at least one instance in it",
				instances.size() > 0);
			HashMap<String, List<String>> instance2SecurityGroupNamesMap =
				new HashMap<String, List<String>>();
			// collect security group descriptions for instances in this ELB
			List<ReservationDescription> describeInstances =
				Ec2.describeInstances(instances);
			List<String> instancesSecurityGroups = new ArrayList<String>();
			for (ReservationDescription desc : describeInstances) {
				for (String group : desc.getGroups()) {
					instancesSecurityGroups.add(group);
					for (Instance instance : desc.getInstances()) {
						String instanceId = instance.getInstanceId();
						List<String> groupNames = 
							instance2SecurityGroupNamesMap.get(instanceId);
						if (groupNames == null) {
							groupNames = new ArrayList<String>();
							instance2SecurityGroupNamesMap.
								put(instanceId, groupNames);
						}
						groupNames.add(group);
					}
				}
			}
			// only get security group descriptions for groups we haven't
			// collected it for already
			instancesSecurityGroups.
				removeAll(securityGroupName2GroupDescriptionMap.keySet());
			if (instancesSecurityGroups.size() > 0) {
				List<GroupDescription> describeSecurityGroups = 
					Ec2.describeSecurityGroups(instancesSecurityGroups);
				for (GroupDescription groupDesc : describeSecurityGroups) {
					securityGroupName2GroupDescriptionMap.
						put(groupDesc.getName(), groupDesc);
				}
			}
			for (Listener listener : listeners) {
				int instancePort = listener.getInstancePort();
				for (Map.Entry<String, List<String>> 
					instance2SecurityGroupNames : 
					instance2SecurityGroupNamesMap.entrySet()) {
					boolean isPortOpen = false;
					String instanceId = instance2SecurityGroupNames.getKey();
					List<String> securityGroupNames = 
						instance2SecurityGroupNames.getValue();
					for (String securityGroupName : securityGroupNames) {
						GroupDescription groupDesc = 
							securityGroupName2GroupDescriptionMap.
								get(securityGroupName);
						List<IpPermission> permissions = 
							groupDesc.getPermissions();
						for (IpPermission permission : permissions) {
							int fromPort = permission.getFromPort();
							int toPort = permission.getToPort();
							if (fromPort <= instancePort && 
								toPort >= instancePort) {
								isPortOpen = true;
							}
							isPortOpen &= permission.getProtocol().
								equalsIgnoreCase("tcp");
							List<String> ipRanges = permission.getIpRanges();
							isPortOpen &= (ipRanges != null && 
								ipRanges.contains("0.0.0.0/0")); 
							if (isPortOpen) {
								break;
							}
						}
						if (isPortOpen) {
							break;
						}
					}
					assertTrue("ELB " + lb.getName() + " has a listener that" +
						" uses instance-port " + instancePort + 
						" and instance " + instanceId + " has that TCP port " +
						"open to the world", isPortOpen);
					System.out.println("ELB " + lb.getName() + " has a " +
						"listener that uses instance-port " + instancePort + 
						" and instance " + instanceId + " has that TCP port " +
						"open to the world.");
				}
			}
		}
	}
	
	@Test
	public void testHealthCheckIsListenerInstancePort() {
		System.out.println("Test: all ELBs have a HealthCheck on a port that the listener directs traffic to");
		for (LoadBalancer lb : LoadBalancers) {
			System.out.println("Load Balancer: " + lb.getName());
			HealthCheck healthCheck = lb.getHealthCheck();
			int healthCheckPort = Integer.parseInt(healthCheck.getTarget().split(":")[1]);
			List<Listener> listeners = lb.getListeners();
			int matchingHealthCheckPort = 0;
			for (Listener listener : listeners) {
				if (healthCheckPort == listener.getInstancePort()) {
					matchingHealthCheckPort = healthCheckPort;
					break;
				}
			}
			assertTrue("ELB" + lb.getName() + " has a configured HealthCheck on port "
				+ healthCheckPort, matchingHealthCheckPort != 0);
			System.out.println("ELB " + lb.getName() + " has a configured " +
				"HealthCheck on listener port " + matchingHealthCheckPort);
		}
	}
	
	@Test
	public void testHealthyInstancesExistInAvailablilityZones() 
		throws EC2Exception, LoadBalancingException {
		System.out.println("Test: all ELBs have InService instances in each " +
				"configured availability zone");
		for (LoadBalancer lb : LoadBalancers) {
			System.out.println("Load Balancer: " + lb.getName());
			List<String> availabilityZones = lb.getAvailabilityZones();
			HashSet<String> leftoverAvailabilityZones = 
				new HashSet<String>(availabilityZones);
			List<String> instances = lb.getInstances();
			List<ReservationDescription> describeInstances = 
				Ec2.describeInstances(instances);
			HashMap<String, String> instance2AvailabilityZoneMap = 
				new HashMap<String, String>();
			for (ReservationDescription res : describeInstances) {
				List<Instance> resInstances = res.getInstances();
				for (Instance resInstance : resInstances) {
					instance2AvailabilityZoneMap.put(resInstance.
						getInstanceId(), 
						resInstance.getAvailabilityZone());
				}
			}
			List<InstanceState> describeInstanceHealth = 
				ELB.describeInstanceHealth(lb.getName());
			HashSet<String> leftoverInstances = new HashSet<String>();
			for (InstanceState instanceHealth : describeInstanceHealth) {
				String state = instanceHealth.getState();
				String instanceId = instanceHealth.getInstanceId();
				String az = instance2AvailabilityZoneMap.get(instanceId);
				if (availabilityZones.contains(az)) {
					if (state.equalsIgnoreCase("InService")) {
						leftoverAvailabilityZones.remove(az);
					}
				} else {
					leftoverInstances.add(instanceId);
				}
			}
			for (String az : leftoverAvailabilityZones) {
				fail("ELB " + lb.getName() + " has no InService instances " +
					"in availability zone " + az);
			}
			System.out.println("ELB " + lb.getName() + " has InService " +
				"instances in each configured availability zone");
			for (String leftoverInstance : leftoverInstances) {
				System.out.println(" WARNING: ELB " + lb.getName() + 
					" contains instance " + leftoverInstance + " in an " +
					"availability zone that is not configured for load " +
					"balancing: " + instance2AvailabilityZoneMap.
						get(leftoverInstance));
			}
		}
	}
}
