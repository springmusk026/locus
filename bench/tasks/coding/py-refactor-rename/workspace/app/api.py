from app.users import fetch_user


def user_json(user_id):
    user = fetch_user(user_id)
    if user is None:
        return {"error": "not found"}
    return {"user": user}
