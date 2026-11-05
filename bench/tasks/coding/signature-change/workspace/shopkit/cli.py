import argparse

from .money import format_amount, parse_amount


def main(argv=None):
    parser = argparse.ArgumentParser(prog="shopkit")
    sub = parser.add_subparsers(dest="cmd", required=True)
    price = sub.add_parser("price", help="format an amount in cents")
    price.add_argument("cents", type=int)
    parse = sub.add_parser("parse", help="parse an amount into cents")
    parse.add_argument("text")
    args = parser.parse_args(argv)
    if args.cmd == "price":
        print(format_amount(args.cents))
    else:
        print(parse_amount(args.text))


if __name__ == "__main__":
    main()
