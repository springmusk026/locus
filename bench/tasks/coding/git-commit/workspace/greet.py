def greet(name, lang="en"):
    if lang == "en":
        return f"Hello, {name}!"
    raise ValueError(f"unsupported language: {lang}")
