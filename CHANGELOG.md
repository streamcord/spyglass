# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## v1.0.3 (2021-05-30)

## Changed

- Base Docker image from `openjdk:16-alpine` to `adoptopenjdk:11-jre-hotspot`, both for consistent security updates and
  a smaller storage/memory footprint

## Updated

- kotlin v1.5.0 -> v1.5.10
- ktor v1.5.4 -> v1.6.0
- kaml v0.33.0 -> v0.34.0
- junit-jupiter v5.7.0 -> v5.7.2

## v1.0.2 (2021-05-16)

### Changed

- Docker coordinates changed to `streamcord/spyglass`
- Changed Dockerfile to multi-stage build and moved original Dockerfile to Dockerfile.host

### Fixed

- Now tolerates a null value for `stream_end_action` in notification documents (defaults to 0)
- Prints a stack trace rather than a possibly-null localized message if the initial connection attempt fails

## v1.0.1 (2021-05-14)

### Updated

- kotlinx.serialization v1.1.0 -> v1.2.1 (significant improvements to JSON encoding and decoding speed)
- kaml v0.31.0 -> v0.33.0
- Gradle wrapper v7.0 -> v7.0.2
