import sys


def port(value):  # TODO: validate the port range
    return int(value)


def fetch(url):
    label = "todo list"
    print(label)
    # TODO: retry on timeout
    return url


if __name__ == "__main__":
    print(port(sys.argv[1]))
