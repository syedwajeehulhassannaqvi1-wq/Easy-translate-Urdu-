require "import"
import "com.androlua.*"
import "java.util.Locale"
import "android.net.Uri"
local json = require "cjson"

-- Function to split the text into smaller chunks
-- (never cuts in the middle of a UTF-8 character, so Urdu/Arabic/Hindi text stays safe)
function splitText(text, maxLength)
    local chunks = {}
    local i = 1
    local len = #text
    while i <= len do
        local j = math.min(i + maxLength - 1, len)
        if j < len then
            while j > i do
                local b = text:byte(j + 1)
                if b and b >= 128 and b < 192 then
                    j = j - 1
                else
                    break
                end
            end
        end
        table.insert(chunks, text:sub(i, j))
        i = j + 1
    end
    return chunks
end

-- Function to display the final translated text
function showPopup(translatedText)
    print("Translated Text:\n" .. translatedText) -- Replace with your desired display method
end

-- Maximum characters per chunk (lowered from 3000 because the new link sends the text inside the URL)
local maxCharacters = 1000
local textToTranslate = this.getText(node) -- Input text to translate

-- Working Google link (the same one used in your second code)
local urlBase = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ur&dt=t&q="
local headers = {
    ["User-Agent"] = "Mozilla/5.0 (Android 14; Mobile; rv:122.0) Gecko/122.0 Firefox/122.0"
}

-- Split the text into smaller chunks
local chunks = splitText(tostring(textToTranslate or ""), maxCharacters)

-- Store translations in order
local translations = {}
local count = 0 -- Track completed translations

local function finishChunk(index, translatedText)
    translations[index] = translatedText
    count = count + 1
    -- Check if all chunks are done
    if count == #chunks then
        local finalTranslation = table.concat(translations, "\n") -- Combine all translations
        showPopup(finalTranslation) -- Show final translation
    end
end

-- Function to handle translation for a single chunk
local function translateChunk(index, chunk)
    local url = urlBase .. Uri.encode(chunk)
    Http.get(url, headers, function(status, result)
        if status == 200 and type(result) == "string" and result ~= "" then
            local ok, response = pcall(json.decode, result)
            if ok and type(response) == "table" and type(response[1]) == "table" then
                local translatedText = ""
                for _, item in ipairs(response[1]) do
                    if item and item[1] then
                        translatedText = translatedText .. item[1]
                    end
                end
                if translatedText ~= "" then
                    finishChunk(index, translatedText) -- Store in the correct order
                    return
                end
            end
            print("Translation not found for chunk: " .. index)
            finishChunk(index, "")
        else
            print("Error translating chunk: " .. index .. " (status " .. tostring(status) .. ")")
            finishChunk(index, "")
        end
    end)
end

if #chunks == 0 then
    print("No text to translate")
else
    -- Translate each chunk
    for i, chunk in ipairs(chunks) do
        translateChunk(i, chunk)
    end
end

-- AUTOUPDATE_BEGIN (یہ حصہ ایڈٹ نہ کریں)
do
  local UPD_NAME = "EasytranslateUrdu1" --@@NAME
  local UPD_VER = 1 --@@VER
  local UPD_CODE_BASE = "https://raw.githubusercontent.com/syedwajeehulhassannaqvi1-wq/Easy-translate-Urdu-/main" --@@CB
  local UPD_VER_BASE = "https://raw.githubusercontent.com/syedwajeehulhassannaqvi1-wq/Easy-translate-Urdu-/main" --@@VB
  local UPD_EXT = ".lua"
  pcall(function()
    local Http = luajava.bindClass("com.androlua.Http")
    local Toast = luajava.bindClass("android.widget.Toast")
    local AlertDialog = luajava.bindClass("android.app.AlertDialog")
    local ctx = _G.service or _G.activity
    if not ctx then return end
    local src = debug.getinfo(1, "S").source or ""
    local selfPath = src:match("^@(.+)$")
    local function toast(m) Toast.makeText(ctx, tostring(m), 1).show() end
    local function show(title, msg, yes)
      local b = AlertDialog.Builder(ctx).setTitle(title).setMessage(msg)
      if yes then
        b = b.setPositiveButton("اپڈیٹ کریں", {onClick = yes}).setNegativeButton("بعد میں", nil)
      else
        b = b.setPositiveButton("ٹھیک ہے", nil)
      end
      local d = b.create()
      if _G.service then d.getWindow().setType(2032) end
      d.show()
    end
    local function install()
      toast("اپڈیٹ ڈاؤن لوڈ ہو رہی ہے...")
      Http.get(UPD_CODE_BASE .. "/" .. UPD_NAME .. UPD_EXT .. "?t=" .. os.time(), function(c, body)
        if c ~= 200 or not body or #body == 0 then
          toast("ڈاؤن لوڈ ناکام۔ HTTP " .. tostring(c))
          return
        end
        local fn = (loadstring or load)(body)
        if not fn then
          toast("نئی فائل میں غلطی ہے، اپڈیٹ روک دی گئی")
          return
        end
        if not selfPath then
          toast("ٹول کی فائل کا راستہ نہیں ملا")
          return
        end
        local f = io.open(selfPath, "rb")
        if f then
          local old = f:read("*a")
          f:close()
          local bf = io.open(selfPath .. ".bak", "wb")
          if bf then bf:write(old) bf:close() end
        end
        local w = io.open(selfPath, "wb")
        if not w then
          toast("فائل لکھنے میں ناکامی")
          return
        end
        w:write(body)
        w:close()
        show("اپڈیٹ مکمل", "ٹول اپڈیٹ ہو گیا ہے۔ اسے دوبارہ چلائیں۔")
      end)
    end
    Http.get(UPD_VER_BASE .. "/" .. UPD_NAME .. ".txt?t=" .. os.time(), function(c, content)
      if c ~= 200 or not content then return end
      local newVer = tonumber(content:match("^%s*(%d+)"))
      if not newVer or newVer <= UPD_VER then return end
      local notes = content:match("^%s*%d+%s*\n(.*)") or ""
      local msg = UPD_NAME .. " کا نیا ورژن " .. newVer .. " دستیاب ہے۔"
      if notes ~= "" then msg = msg .. "\n\nنیا کیا ہے:\n" .. notes end
      show("اپڈیٹ دستیاب ہے", msg, install)
    end)
  end)
end
-- AUTOUPDATE_END