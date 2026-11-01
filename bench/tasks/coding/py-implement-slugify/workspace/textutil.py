"""Text helpers."""


def slugify(text, max_length=50):
    """Turn a title into a URL slug.

    Rules, in order:
    - Letters with accents become plain ASCII ("Crème Brûlée" -> "creme brulee"); other
      non-ASCII characters are dropped.
    - Lowercase everything.
    - Any run of characters that aren't a-z or 0-9 becomes a single hyphen.
    - No hyphen at the start or the end.
    - At most max_length characters; when cut, cut at the last hyphen at or before
      max_length (so no word is split), unless there is none, then cut at max_length.
    - An empty result is returned as "n-a".

    >>> slugify("Hello, World!")
    'hello-world'
    """
    raise NotImplementedError
