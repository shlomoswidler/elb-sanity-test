# elb-sanity-test

#### by Shlomo Swidler

Are my AWS Elastic Load Balancers set up properly?
A test you can run to check your ELB for common gotchas.
See my article for a detailed examination of the tests that this utility conducts: http://shlomoswidler.com/2009/09/solving-common-elb-problems-with-sanity.html

The original Java implementation from Google Code and described in the article
is here in GitHub as branch **0.1**.

The **master** branch has been rewritten in Ruby and updates the tests for some of the
technology changes that AWS ELB has undergone in the past four years, such as
supporting the ELB-specific security group.

# Setup

1. Make sure you have a Ruby 1.9.3 environment.
2. Get this project from GitHub: https://github.com/shlomoswidler/elb-sanity-test
3. Make the ruby script executable: `chmod +x elb-sanity-check.rb`

# Usage
```
$ ./elb-sanity-check.rb
Commands:
  elb-sanity-test.rb check           # Checks ELBs for sanity
  elb-sanity-test.rb help [COMMAND]  # Describe available commands or one spe...

Options:
  -v, [--verbose], [--no-verbose]  # Show verbose output
  -d, [--debug], [--no-debug]      # Show debug output. Includes verbose output.
```
```
$ ./elb-sanity-test.rb help check
Usage:
  elb-sanity-test.rb check

Options:
      [--aws-access-key-id=AWS_ACCESS_KEY_ID]          # The AwsAccessKeyId
                                                       # Default: AWS_ACCESS_KEY_ID environment variable
      [--aws-secret-access-key=AWS_SECRET_ACCESS_KEY]  # The AwsSecretAccessKey
                                                       # Default: AWS_SECRET_ACCESS_KEY environment variable
      [--region=one two three]                         # AWS region(s) to check. Defaults to all regions. Example: --regions=us-east-1 us-west-2 eu-west-1
                                                       # Default: ["<all>"]
      [--name=one two three]                           # Name of ELB(s) to check. If not specified, checks all ELBs in the specified region(s). Example: --name=elbName1 elbName2
                                                       # Default: ["<all>"]
  -v, [--verbose], [--no-verbose]                      # Show verbose output
  -d, [--debug], [--no-debug]                          # Show debug output. Includes verbose output.

Checks ELBs for sanity
```
### Example
Check all ELBs in region us-east-1

    $ ./elb-sanity-test.rb --aws-access-key-id=AKIASAMPLE --aws-secret-access-key=SUPERSECRETACCESSKEY check --region=us-east-1

Check only the ELBs named **tornado** and **drizzle** in region us-west-2, relying on the environment variables for the AWS credentials

    $ ./elb-sanity-test.rb check --name=tornado drizzle --region=us-west-2
### License
Licensed under the Apache 2.0 license. See the file `LICENSE` for complete info.
