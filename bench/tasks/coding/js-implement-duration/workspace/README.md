# duration

`parseDuration(text)` turns a human duration into milliseconds.

- Units: `ms`, `s`, `m`, `h`, `d` (days). Units are case-insensitive.
- Several parts can follow each other, with or without spaces: `1h30m`, `1h 30m`, `2d 4h`.
- Numbers may have a decimal part: `1.5h` is 5400000.
- Surrounding whitespace is ignored.
- Anything else (an empty string, an unknown unit, a number without a unit, a negative number)
  throws a `TypeError`.

```js
const { parseDuration } = require('./duration');
parseDuration('1h30m'); // 5400000
```
