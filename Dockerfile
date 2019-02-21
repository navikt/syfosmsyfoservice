FROM navikt/java:11
COPY config.json .
COPY build/libs/syfosmsyfoservice-*-all.jar app.jar
ENV JAVA_OPTS='-Dlogback.configurationFile=logback-remote.xml'
ENV APPLICATION_PROFILE="remote"
