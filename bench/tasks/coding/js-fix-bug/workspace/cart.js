'use strict';

const CODES = { SAVE10: 0.10, HALF: 0.5 };

/** Sum of price * qty, with an optional discount code applied to the whole cart, rounded to cents. */
function total(items, code) {
  let sum = 0;
  for (const item of items) {
    sum += item.price * item.qty;
    if (code && CODES[code]) {
      sum = sum - sum * CODES[code];
    }
  }
  return Math.round(sum * 100) / 100;
}

module.exports = { total, CODES };
