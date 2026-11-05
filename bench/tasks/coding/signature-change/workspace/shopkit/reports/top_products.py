from ..money import format_amount


def top_products(sales, n=3):
    """sales: {name: cents}. The n best sellers, best first, with their revenue."""
    best = sorted(sales.items(), key=lambda kv: (-kv[1], kv[0]))[:n]
    names = [name for name, _ in best]
    amounts = list(map(format_amount, [c for _, c in best]))
    return ", ".join(f"{n} {a}" for n, a in zip(names, amounts))
