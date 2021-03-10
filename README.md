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
    connection: <connection string for replica set, e.g. "mongodb://localhost">
    database: <name of db, e.g. "eventsub">
    collections:
        subscriptions: <name of collection, e.g. "subscriptions">
        workers: <name of collection, e.g. "workers">
        notifications: <name of collection, e.g. "notifications">

aqmp:
    connection: <connection string, e.g. "localhost">
    exchange: <exchange name, e.g. "amq.direct">
    queue: <queue name, e.g. "events">
```

## Running

```bash
$ export SPYGLASS_CLIENT_ID="<Twitch client ID>"
$ export SPYGLASS_CLIENT_SECRET="<Twitch client secret>"
$ export SPYGLASS_WEBHOOK_CALLBACK="<webhook callback URL, e.g. my.website/webhooks/callback" 
$ java -jar spyglass-indev-all.jar
```

## Copyright

Copyright © 2021 Streamcord, LLC

Distributed under the Boost Software License, Version 1.0.

[streamcord-badge]: https://img.shields.io/badge/Streamcord-In_Development-9146ff

[discord-guild-badge]: https://discordapp.com/api/guilds/294215057129340938/widget.png?style=shield "Discord Server"

[github-license-badge]: https://img.shields.io/github/license/streamcord/spyglass?color=lightgrey
