Example BBS modules.

Copy one to the phone, import it from the BBS tab under Settings and admins, and turn it
on. Modules are off when imported, because adding a file and running it should be two
separate decisions.

## What a module gets

Three globals are the whole contract: `keyword`, `help` and `handle(request)`. The request
table holds `from` (the sender's node id), `name`, `args` (everything after the keyword),
`keyword`, `direct`, `channel`, `snr` and `hops`. Return a string to reply, or nil to say
nothing.

The `mesh` table is everything a module can reach outside itself:

    mesh.now()            seconds since the epoch
    mesh.log(text)        a line in the Meshdroid log
    mesh.get(key)         read from this module's own storage
    mesh.set(key, value)  write, or pass nil to delete

## What a module cannot do

There is no io, no os, no require, no coroutines, and no way to load more code at runtime.
There is no way to send a message: a module returns text and the engine decides what happens
to it, so the rate limits and the airtime spacing hold whatever a module does.

Storage is scoped per file, capped at 100 keys of 2 KB each. A script gets 200,000 virtual
machine instructions and 250 milliseconds per call, after which it is aborted and the sender
is told the command is not working. A module that throws is logged and disabled for that
call only.

Keywords that a built-in already uses are refused, so nothing imported can take over
`admin`, `board` or `mail`.
