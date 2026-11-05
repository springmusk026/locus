from .. import money
from .templates import RECEIPT


def receipt(name, order, cents):
    return RECEIPT.format(name=name, order=order, total=money.format_amount(cents))
