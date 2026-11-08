FROM maven:3.9-eclipse-temurin-21

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git procps python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY app/ /app/

# Pre-fetch the dependencies and build plugins so the agent does not wait for downloads.
RUN mvn -B -q dependency:go-offline compile \
    && rm -rf target
