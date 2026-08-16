-- ArenaMP Android cjson compatibility module.
-- It intentionally uses the bundled dkjson implementation so the dedicated
-- server does not depend on a loadable cjson.so in external storage.
local dkjson = require("dkjson")
local cjson = {}
local empty_table_as_object = false

function cjson.encode_sparse_array(...) return true end
function cjson.encode_invalid_numbers(...) return true end
function cjson.decode_null_as_lightuserdata(...) return true end
function cjson.encode_empty_table_as_object(value)
    if value ~= nil then empty_table_as_object = not not value end
    return empty_table_as_object
end

local array_mt = { __jsontype = "array" }
local function prepare(value, seen)
    if type(value) == "number" and (value ~= value or value == math.huge or value == -math.huge) then
        return dkjson.null
    end
    if type(value) ~= "table" then return value end
    seen = seen or {}
    if seen[value] then return seen[value] end
    local out = {}
    seen[value] = out
    local empty = true
    for k, v in pairs(value) do
        empty = false
        out[prepare(k, seen)] = prepare(v, seen)
    end
    if empty and not empty_table_as_object then setmetatable(out, array_mt) end
    return out
end

function cjson.encode(value)
    local ok, encoded = pcall(dkjson.encode, prepare(value))
    if not ok then error(encoded) end
    return encoded
end

function cjson.decode(text)
    local value, _, err = dkjson.decode(text, 1, nil)
    if err then error(err) end
    return value
end

return cjson
