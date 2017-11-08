FROM java:8


RUN adduser --disabled-password --gecos "" -u 1000 chronix && mkdir -p /code && chown chronix:chronix /code

USER chronix

WORKDIR /code
ADD --chown=chronix:chronix . /code

RUN ./gradlew --no-daemon clean build copyIntegrationRuntimeLibs

CMD ["./gradlew", "--no-daemon", "startSolrForeground"]
