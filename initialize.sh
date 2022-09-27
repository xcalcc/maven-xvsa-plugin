ARCHE="plugin"
# mvn -B archetype:generate  -DarchetypeGroupId=org.apache.maven.archetypes   -DgroupId=io.xc5.plugin -DartifactId=mvnscan

mvn archetype:generate -DarchetypeGroupId=org.apache.maven.archetypes -DarchetypeArtifactId=maven-archetype-plugin -DarchetypeVersion=1.4 -DgroupId=io.xc5 -DartifactId=mavenplugin
