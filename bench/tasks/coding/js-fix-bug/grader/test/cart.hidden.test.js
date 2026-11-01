'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { total } = require('../cart.js');

test('HALF on three lines', () => {
  assert.equal(total([{ price: 3, qty: 1 }, { price: 5, qty: 2 }, { price: 7, qty: 1 }], 'HALF'), 10);
});

test('empty cart', () => {
  assert.equal(total([], 'SAVE10'), 0);
});

test('rounds to cents', () => {
  assert.equal(total([{ price: 0.1, qty: 3 }], 'SAVE10'), 0.27);
});
