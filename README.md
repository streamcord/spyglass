# Spyglass

[![Streamcord (In Development)][streamcord-badge]](https://streamcord.io/twitch/)
[![Discord][discord-guild-badge]](https://discord.com/invite/UNYzJqV)
[![Boost License 1.0][github-license-badge]](https://www.boost.org/users/license.html)

Spyglass is a microservice implementation of the Twitch EventSub API, designed to efficiently create and manage
subscriptions. It's currently being developed for use with Streamcord, but could be adapted for use with other services,
and can serve as a reference for other implementations of EventSub.

## Status

Spyglass is currently in the pre-alpha phase of development.

## Building

First, install a JDK, version 11 or greater.

```
$ ./gradlew shadowJar
```

This will create a file in the `build/libs` folder named `spyglass-indev-all.jar`.

## Setup

Spyglass, in its current state, requires connection to both a MongoDB replica set and an AQMP client. Set both of these
up first, then proceed to the next step.

Create a file in the same directory as the Spyglass jarfile named `spyglass.yml`. This file will contain the
configuration Spyglass will use. It should be formatted as such:

```
mongo:
  connection: <connection string for replica set, e.g. "mongodb://localhost?replicaSet=replicaset">
  database: <name of db, e.g. "eventsub">
  collections:
    subscriptions: <name of collection, e.g. "subscriptions">
    notifications: <name of collection, e.g. "notifications">

twitch:
  client_id: <Twitch client ID, e.g. "yuk4id1awfrr5qkj5yh8qzlgpg66">
  client_secret: <Twitch client secret, e.g. "5j48e47jhzb55o7zainz7e7niist">
  base_callback: <Webhook callback URL for EventSub notifications, e.g. "eventsub.streamcord.io">

aqmp:
  connection: <connection string, e.g. "localhost">
  queue: <queue name, e.g. "events">
```

## Running

```bash
$ export SPYGLASS_WORKER_INDEX="<The index of this Spyglass worker>"
$ export SPYGLASS_WORKER_TOTAL="<Total number of Spyglass workers that will be running>"
$ java -jar spyglass-indev-all.jar
```

The application will create an HTTP server on port 8080, and will send `https://INDEX.BASE_CALLBACK` as the webhook
event link to Twitch, where `BASE_CALLBACK` is provided in `spyglass.yml` and `INDEX` is the value
of `SPYGLASS_WORKER_INDEX`. For example, if the base callback is set to `eventsub.streamcord.io` and the value
of `SPYGLASS_WORKER_INDEX` is `0`, then the URL sent to Twitch for event callbacks will
be `https://0.eventsub.streamcord.io`.

Spyglass cannot run a functional HTTPS server on its own; it requires a proxy that can generate certificates and
redirect the HTTPS calls from port 443 to port 8080 so Spyglass can pick them up. This can be accomplished using
an [Nginx reverse proxy with Docker Compose.](https://www.domysee.com/blogposts/reverse-proxy-nginx-docker-compose).

## Copyright

Copyright © 2021 Streamcord, LLC

Distributed under the Boost Software License, Version 1.0.

[streamcord-badge]: https://img.shields.io/badge/Streamcord-In_Development-9146ff

[discord-guild-badge]: https://discordapp.com/api/guilds/294215057129340938/widget.png?style=shield "Discord Server"

[github-license-badge]: https://img.shields.io/github/license/streamcord/spyglass?color=lightgrey
