from . import money

RATES = [(500, 395), (2000, 695), (10000, 1295)]  # (max grams, cents)


def quote(weight_grams):
    for limit, cents in RATES:
        if weight_grams <= limit:
            return cents
    raise ValueError("too heavy to ship")


def label(weight_grams):
    return "Shipping: " + money.format_amount(quote(weight_grams))
