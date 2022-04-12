The servers/AMIs need:

* Java installed (11+)
* An SSH key on your machine, added to your ssh-agent, whose username matches with the SSH username in PerformanceTest#main()
* The sar and mpstat utilities installed ->
    * Ubuntu: sudo apt install sysstat
* For Flamegraphs to work ->
    * JDK Debug symbols: https://github.com/jvm-profiling-tools/async-profiler#installing-debug-symbols
    * Allow capturing Kernel call stack events:  https://github.com/jvm-profiling-tools/async-profiler#basic-usage

If you want to use a database for testing:

* Create an RDS database and add its properties to application.properties
* Execute database.sql against your new RDS database
* Change MODE in PerformanceTest to "DEPLOY_DB"