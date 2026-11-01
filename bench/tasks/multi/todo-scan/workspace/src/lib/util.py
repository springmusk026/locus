def initials(name):
    parts = name.split()
    return "".join(p[0] for p in parts)


def slug(name):
    # TODO: handle unicode names
    return name.lower().replace(" ", "-")
