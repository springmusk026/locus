'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { parseDuration } = require('../duration.js');

test('single units', () => {
  assert.equal(parseDuration('250ms'), 250);
  assert.equal(parseDuration('2s'), 2000);
  assert.equal(parseDuration('3m'), 180000);
  assert.equal(parseDuration('1h'), 3600000);
  assert.equal(parseDuration('1d'), 86400000);
});

test('combined, spaces and case', () => {
  assert.equal(parseDuration('1h30m'), 5400000);
  assert.equal(parseDuration(' 2D 4H '), 2 * 86400000 + 4 * 3600000);
  assert.equal(parseDuration('1m 500ms'), 60500);
});

test('decimals', () => {
  assert.equal(parseDuration('1.5h'), 5400000);
  assert.equal(parseDuration('0.25s'), 250);
});

test('errors', () => {
  for (const bad of ['', '   ', '10', '5y', '-1h', 'h', '1h foo']) {
    assert.throws(() => parseDuration(bad), TypeError, `expected TypeError for ${JSON.stringify(bad)}`);
  }
});
