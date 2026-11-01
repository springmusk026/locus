'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { total } = require('../cart.js');

const items = [
  { price: 20, qty: 2 },
  { price: 10, qty: 2 },
];

test('no code', () => {
  assert.equal(total(items), 60);
});

test('SAVE10 takes 10% off the whole cart', () => {
  assert.equal(total(items, 'SAVE10'), 54);
});

test('unknown code is ignored', () => {
  assert.equal(total(items, 'NOPE'), 60);
});
