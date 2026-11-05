from ..money import format_amount


def monthly_revenue(orders, month):
    """orders: (iso_date, cents) pairs. month: 'YYYY-MM'."""
    total = sum(c for d, c in orders if d.startswith(month))
    return f"Revenue for {month}: {format_amount(total)}"
