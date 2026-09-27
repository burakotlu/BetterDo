"""Email-verified accounts and revocable sessions for the BetterDo service."""
import hashlib
import hmac
import re
import secrets
import smtplib
import ssl
import time
import uuid
from email.message import EmailMessage


class SmtpMailer:
    def __init__(self, host, port, username, password, sender):
        self.host, self.port = host, port
        self.username, self.password, self.sender = username, password, sender

    def send_code(self, address, code):
        if not self.host or not self.sender:
            raise RuntimeError("Email delivery is not configured on this server")
        message = EmailMessage()
        message["From"] = self.sender
        message["To"] = address
        message["Subject"] = "BetterDo email confirmation"
        message.set_content("Your BetterDo confirmation code is {}. It expires in 15 minutes.\n".format(code))
        if self.port == 465:
            with smtplib.SMTP_SSL(self.host, self.port, timeout=10, context=ssl.create_default_context()) as smtp:
                if self.username:
                    smtp.login(self.username, self.password)
                smtp.send_message(message)
        else:
            with smtplib.SMTP(self.host, self.port, timeout=10) as smtp:
                smtp.starttls(context=ssl.create_default_context())
                if self.username:
                    smtp.login(self.username, self.password)
                smtp.send_message(message)


class AccountService:
    def __init__(self, database, code_secret, mailer, clock=time.time):
        self.database, self.secret, self.mailer, self.clock = database, code_secret.encode("utf-8"), mailer, clock
        if len(self.secret) < 32:
            raise ValueError("Account code secret must be at least 32 characters")

    @staticmethod
    def normalize_email(value):
        if not isinstance(value, str):
            raise ValueError("Enter a valid email address")
        email = value.strip().lower()
        if len(email) > 254 or not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
            raise ValueError("Enter a valid email address")
        return email

    @staticmethod
    def validate_name(value):
        if not isinstance(value, str) or not 1 <= len(value.strip()) <= 80 or any(ord(c) < 32 for c in value):
            raise ValueError("Enter a display name of at most 80 characters")
        return value.strip()

    @staticmethod
    def validate_password(value):
        if not isinstance(value, str) or not 8 <= len(value) <= 256:
            raise ValueError("Use a password with 8 to 256 characters")
        return value

    @staticmethod
    def password_hash(password, salt):
        return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 310000)

    def code_hash(self, email, code):
        return hmac.new(self.secret, (email + ":" + code).encode("utf-8"), hashlib.sha256).digest()

    def limited(self, db, email, action, maximum, window):
        now = self.clock()
        count = db.execute("SELECT COUNT(*) FROM auth_attempts WHERE email=? AND action=? AND attempted_at>?",
                           (email, action, now - window)).fetchone()[0]
        if count >= maximum:
            raise ValueError("Too many attempts. Try again later")
        db.execute("INSERT INTO auth_attempts VALUES (?,?,?)", (email, action, now))
        db.execute("DELETE FROM auth_attempts WHERE attempted_at<?", (now - 86400,))

    def reserve_attempt(self, email, action, maximum, window):
        # Commit attempts independently so rejected credentials still count.
        with self.database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self.limited(db, email, action, maximum, window)

    def signup(self, email, password, display_name):
        email = self.normalize_email(email)
        password = self.validate_password(password)
        display_name = self.validate_name(display_name)
        code = "{:08d}".format(secrets.randbelow(100000000))
        now = self.clock()
        with self.database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self.limited(db, email, "signup", 3, 3600)
            total = db.execute("SELECT COUNT(*) FROM auth_attempts WHERE action='signup' AND attempted_at>?", (now - 86400,)).fetchone()[0]
            if total > 100:
                raise ValueError("New account requests are temporarily full")
            existing = db.execute("SELECT id,verified FROM accounts WHERE email=?", (email,)).fetchone()
            if existing and existing["verified"]:
                return
            salt = secrets.token_bytes(16)
            hashed = self.password_hash(password, salt)
            if existing:
                db.execute("UPDATE accounts SET display_name=?,password_salt=?,password_hash=?,code_hash=?,code_expires_at=?,code_attempts=0 WHERE id=?",
                           (display_name, salt, hashed, self.code_hash(email, code), now + 900, existing["id"]))
            else:
                db.execute("INSERT INTO accounts (id,email,display_name,password_salt,password_hash,code_hash,code_expires_at,created_at) VALUES (?,?,?,?,?,?,?,?)",
                           (uuid.uuid4().hex, email, display_name, salt, hashed, self.code_hash(email, code), now + 900, now))
            self.mailer.send_code(email, code)

    def verify(self, email, code):
        email = self.normalize_email(email)
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{8}", code):
            raise ValueError("Enter the eight-digit confirmation code")
        self.reserve_attempt(email, "verify", 8, 900)
        with self.database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM accounts WHERE email=?", (email,)).fetchone()
            if not row or row["verified"] or row["code_attempts"] >= 5 or not row["code_hash"] or row["code_expires_at"] < self.clock():
                raise ValueError("Invalid or expired confirmation code")
            if not hmac.compare_digest(row["code_hash"], self.code_hash(email, code)):
                db.execute("UPDATE accounts SET code_attempts=code_attempts+1 WHERE id=?", (row["id"],))
            else:
                db.execute("UPDATE accounts SET verified=1,code_hash=NULL,code_expires_at=NULL WHERE id=?", (row["id"],))
                return self._new_session(db, row["id"])
        raise ValueError("Invalid or expired confirmation code")

    def signin(self, email, password):
        email = self.normalize_email(email)
        password = self.validate_password(password)
        self.reserve_attempt(email, "signin", 8, 900)
        with self.database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM accounts WHERE email=?", (email,)).fetchone()
            salt = row["password_salt"] if row else bytes(16)
            actual = self.password_hash(password, salt)
            if not row or not hmac.compare_digest(row["password_hash"], actual):
                raise ValueError("Invalid email or password")
            if not row["verified"]:
                raise ValueError("Confirm your email before signing in")
            return self._new_session(db, row["id"])

    def _new_session(self, db, account_id):
        token = secrets.token_urlsafe(32)
        db.execute("INSERT INTO account_sessions VALUES (?,?,?)", (hashlib.sha256(token.encode("ascii")).digest(), account_id, self.clock() + 30 * 86400))
        user = db.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()
        return {"access_token": token, "refresh_token": token, "user": self.public_user(user)}

    @staticmethod
    def public_user(row):
        return {"id": row["id"], "email": row["email"], "display_name": row["display_name"], "verified": bool(row["verified"])}

    def authenticate(self, token):
        if not isinstance(token, str) or len(token) > 128 or not token:
            return None
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        with self.database.connect() as db:
            row = db.execute("SELECT a.id FROM account_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=? AND s.expires_at>? AND a.verified=1",
                             (digest, self.clock())).fetchone()
            return row["id"] if row else None

    def session(self, token):
        account_id = self.authenticate(token)
        if not account_id:
            raise ValueError("Your session has expired. Sign in again")
        with self.database.connect() as db:
            db.execute("UPDATE account_sessions SET expires_at=? WHERE token_hash=?", (self.clock() + 30 * 86400, hashlib.sha256(token.encode("utf-8")).digest()))
            user = db.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()
            return {"access_token": token, "refresh_token": token, "user": self.public_user(user)}

    def update_name(self, token, name):
        account_id = self.authenticate(token)
        if not account_id:
            raise ValueError("Sign in to update your account")
        name = self.validate_name(name)
        with self.database.connect() as db:
            db.execute("UPDATE accounts SET display_name=? WHERE id=?", (name, account_id))
            return self.public_user(db.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone())

    def signout(self, token):
        with self.database.connect() as db:
            db.execute("DELETE FROM account_sessions WHERE token_hash=?", (hashlib.sha256(token.encode("utf-8")).digest(),))
