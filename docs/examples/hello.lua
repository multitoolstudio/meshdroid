-- A BBS module. Drop this on the phone, import it from the BBS settings screen,
-- then turn it on. It answers "hello" on a channel with the prefix, or in a direct
-- message to the node.
--
-- Three globals make a module: the keyword that selects it, the line the help
-- listing prints, and handle(request), which returns the reply or nil for silence.

keyword = "hello"
help = "hello - say hello and count who has"

function handle(request)
  -- What arrives: from (!nodeid), name, args, keyword, direct, channel, snr, hops.
  local seen = tonumber(mesh.get("count:" .. request.from)) or 0
  seen = seen + 1
  mesh.set("count:" .. request.from, tostring(seen))

  if seen == 1 then
    return "Hello " .. request.name .. ", first time here."
  end
  return "Hello again " .. request.name .. ", that is " .. seen .. " times."
end
