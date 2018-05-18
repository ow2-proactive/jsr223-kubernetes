# jsr223-kubernetes
Kubernetes script engine for ProActive Node &amp; Server

## Build
Run `./gradlew build` to create a JAR.

## Usage
Add JAR to classpath or $PROACTIVE_HOME/addons; it will make the script engine discoverable with "kubernetes" as a
script engine name. More information [here](http://docs.oracle.com/javase/6/docs/technotes/guides/scripting/programmer_guide/index.html).
