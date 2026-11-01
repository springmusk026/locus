import sys

from app import users


def main(argv):
    user = users.fetch_user(int(argv[1]))
    print(user["name"] if user else "unknown")


if __name__ == "__main__":
    main(sys.argv)
