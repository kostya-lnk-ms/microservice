Microservice
===================================

To build this project use

    mvn install

To run this project

    mvn exec:java 
	
That will start the service bound to the port 9000
    
To run tests

    mvn test
	
The initial spec looks to disallow workflows with number of steps equal to 1

Since:
 "initial value of current step should be 0"
 "current step can be incremented by 1 if current step < number of steps - 1"
So for a workflow with number of steps 1, we'd have 0 < 1 - 1 (which is false)
Also, if an execution is created for a workflow with 1 step, it'd be a subject for cleanup, since
"current step (which is 0) >= number of steps (which is 1) - 1"
e.g. 0 >= 0
