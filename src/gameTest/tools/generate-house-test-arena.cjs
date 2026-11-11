'use strict';

// Development-only GameTest arena; no runtime dependency and no authored house content.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');

function i32(value) {
  const bytes = Buffer.alloc(4);
  bytes.writeInt32BE(value);
  return bytes;
}
function name(value) {
  const bytes = Buffer.from(value, 'utf8');
  const size = Buffer.alloc(2);
  size.writeUInt16BE(bytes.length);
  return Buffer.concat([size, bytes]);
}
function named(type, key, payload) {
  return Buffer.concat([Buffer.from([type]), name(key), payload]);
}
function compound(entries) {
  return Buffer.concat([...entries, Buffer.from([0])]);
}
function list(type, values) {
  return Buffer.concat([Buffer.from([type]), i32(values.length), ...values]);
}
function intList(values) {
  return list(3, values.map(i32));
}

function arena(file, size) {
const body = compound([
  named(3, 'DataVersion', i32(3955)),
  named(9, 'size', intList(size)),
  named(9, 'palette', list(10, [compound([named(8, 'Name', name('minecraft:air'))])])),
  named(9, 'blocks', list(10, [
    compound([named(9, 'pos', intList([0, 0, 0])), named(3, 'state', i32(0))])
  ])),
  named(9, 'entities', list(10, []))
]);
const uncompressed = named(10, '', body);
const compressed = zlib.gzipSync(uncompressed, {level: 9});
const destination = path.resolve(__dirname,
  '../resources/data/jobless_reincarnation/structure/' + file);
fs.mkdirSync(path.dirname(destination), {recursive: true});
fs.writeFileSync(destination, compressed);
console.log(JSON.stringify({
  destination,
  size,
  bytes: compressed.length,
  sha256: crypto.createHash('sha256').update(compressed).digest('hex')
}));
}
arena('house_h01_empty.nbt', [40, 20, 40]);
arena('house_v2_empty.nbt', [46, 24, 46]);
