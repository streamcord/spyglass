FROM openjdk:17-jdk-alpine

ENV APPLICATION_USER ktor
RUN adduser -D -g '' $APPLICATION_USER

RUN mkdir /var/app
RUN chown -R $APPLICATION_USER /var/app

USER $APPLICATION_USER

COPY ./spyglass-*-all.jar /var/app/spyglass.jar
WORKDIR /var/app

CMD ["java", "-server", "-jar", "spyglass.jar"]
