# tl;dr

This demo app demonstrates how to create a simple non-blocking HTTP server based on Reactor and NIO


there are two versions:

## java

### how to build:


`mvn clean compile`


### how to run:


`mvn exec:java -Dexec.mainClass="com.github.pk11.rnio.HttpServer"`

then visit `http://localhost:9999`


## scala


### how to build:

`mvn clean compile`

### how to run:

 mvn exec:java -Dexec.mainClass="com.github.pk11.rnio.scala.DemoHttpServer"

then visit `http://localhost:9999`
