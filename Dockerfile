FROM gradle:7.0.2-jdk11

WORKDIR /var/build
COPY ./ /var/build

RUN gradle shadowJar --no-daemon

FROM openjdk:16-jdk-alpine

ENV APPLICATION_USER ktor
RUN adduser -D -g '' $APPLICATION_USER

RUN mkdir /var/app
RUN chown -R $APPLICATION_USER /var/app

USER $APPLICATION_USER

COPY --from=0 /var/build/build/libs/spyglass-*-all.jar /var/app/spyglass.jar
WORKDIR /var/app

CMD ["java", "-server", "-jar", "spyglass.jar"]
