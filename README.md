A simple Reactor/NIO based non-blocking HTTP server
===================================================

there are two versions:

java
====

how to build:
=============

`mvn clean compile`


how to run:
===========

`mvn exec:java -Dexec.mainClass="com.github.pk11.rnio.HttpServer"`

then visit `http://localhost:9999`


scala
=====

how to build:
============
`mvn clean compile`

how to run:
===========
 mvn exec:java -Dexec.mainClass="com.github.pk11.rnio.scala.DemoHttpServer"

then visit `http://localhost:9999`
