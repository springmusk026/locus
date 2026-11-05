"""Money helpers. Amounts are integers in cents."""


def format_amount(cents):
    """Format an amount in cents as euros: 1250 -> '€12.50', 123456 -> '€1,234.56'."""
    return "€{:,.2f}".format(cents / 100)


def parse_amount(text):
    """'12.50', '€12.50' or '1,234.56' -> cents."""
    cleaned = text.strip().lstrip("€$£").replace(",", "")
    return round(float(cleaned) * 100)
