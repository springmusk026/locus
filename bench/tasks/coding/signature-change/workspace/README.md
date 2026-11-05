# shopkit

A small shop backend. Amounts are integers in cents everywhere.

- `shopkit/money.py`: formatting and parsing amounts
- `shopkit/cart.py`, `shopkit/catalog.py`, `shopkit/discounts.py`, `shopkit/shipping.py`
- `shopkit/invoice.py`: invoices (each invoice has its own currency)
- `shopkit/reports/`: monthly and top-product reports
- `shopkit/emails/`: receipt emails
- `shopkit/cli.py`: `python3 -m shopkit.cli price 1250`

Run the tests with `python3 -m unittest`.
