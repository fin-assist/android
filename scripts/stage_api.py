"""Bounded public-stage client; credentials never enter reports, files or exception text."""
from __future__ import annotations

import json
import re
import secrets
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field

BASE = "https://stage.plainfin.ru/"
PHONE = re.compile(r"\+7000000[0-9]{4}\Z")
MAX_BODY = 16 * 1024 * 1024


class StageFailure(Exception):
    """A safe diagnostic code, never a remote body or URL containing credentials."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise StageFailure("stage_redirect_refused")


@dataclass
class OwnedAccount:
    run_id: str
    phone: str
    user_id: str
    access: str = field(repr=False)
    refresh: str = field(repr=False)
    deleted: bool = False
    login_ready_at: float = 0


class StageApi:
    def __init__(self, run_id: str, version: str, opener=None):
        if str(uuid.UUID(run_id)) != run_id:
            raise ValueError("invalid run id")
        if not re.fullmatch(r"android/\d+\.\d+\.\d+\+\d+", version):
            raise ValueError("invalid client version")
        self.run_id, self.version = run_id, version
        self.opener = opener or urllib.request.build_opener(NoRedirect())
        self.owned: list[OwnedAccount] = []
        self.used: set[str] = set()
        self.unused: set[str] = set()
        self.secrets: set[str] = set()
        self.requests = 0

    def request(self, method, path, payload=None, *, token=None, headers=None, raw=None):
        # Neither an absolute URL nor path traversal can change the fixed HTTPS trust boundary.
        if not re.fullmatch(r"v1/[A-Za-z0-9_./?=&%-]+", path) or ".." in path:
            raise StageFailure("unsafe_stage_path")
        hs = {"X-Client-Version": self.version, "X-Request-Id": str(uuid.uuid4()),
              "Accept": "application/json"}
        if token:
            self.secrets.add(token)
            hs["Authorization"] = "Bearer " + token
        data = raw
        if payload is not None:
            data = json.dumps(payload).encode()
            hs["Content-Type"] = "application/json"
        hs.update(headers or {})
        for name, value in hs.items():
            if name.lower() in ("x-verification-token", "authorization"):
                self.secrets.add(value)
        req = urllib.request.Request(BASE + path, data=data, headers=hs, method=method)
        self.requests += 1
        try:
            with self.opener.open(req, timeout=30) as response:
                body = response.read(MAX_BODY + 1)
                status = response.status
                kind = response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as error:
            # Do not read, interpolate or log a remote error body, phone or token.
            raise StageFailure("stage_http_" + str(error.code)) from None
        except (OSError, ValueError):
            raise StageFailure("stage_transport_failure") from None
        if len(body) > MAX_BODY:
            raise StageFailure("stage_body_limit")
        if not body:
            return None
        try:
            if "text/event-stream" in kind:
                events = []
                for frame in re.split(rb"\r?\n\r?\n", body):
                    parts = [line[5:].strip() for line in frame.splitlines() if line.startswith(b"data:")]
                    if parts:
                        events.append(json.loads(b"\n".join(parts)))
                return events
            return json.loads(body)
        except (ValueError, UnicodeError):
            raise StageFailure("stage_invalid_response") from None

    def phone_candidate(self):
        for _ in range(100):
            phone = "+7000000" + f"{secrets.randbelow(9997) + 3:04d}"
            if phone not in self.used:
                self.used.add(phone)
                return phone
        raise StageFailure("synthetic_namespace_exhausted")

    def create(self):
        """Only a successful new registration grants ownership; collisions are never adopted."""
        document = self.request("GET", "v1/consents/personal_data")
        for _ in range(8):
            phone = self.phone_candidate()
            verification = self.request("POST", "v1/auth/phone", {
                "phone": phone, "pd_consent_accepted": True,
                "pd_consent_version": document["version"], "age_confirmed": True,
            }, headers={"Idempotency-Key": str(uuid.uuid4())})
            verification_token = verification["verification_token"]
            ready_at = self.verification_ready_at(verification)
            self.secrets.add(verification_token)
            events = self.request("GET", "v1/auth/phone/events", headers={
                "X-Verification-Token": verification_token, "Accept": "text/event-stream",
            })
            result = next((event for event in events if event.get("status") == "verified"), None)
            if result is None:
                raise StageFailure("phone_not_verified")
            for key in ("access_token", "refresh_token", "registration_token"):
                if result.get(key):
                    self.secrets.add(result[key])
            if result.get("is_new_user") is not True:
                # No profile reads, data mutation or cleanup of this existing identity.
                continue
            registered = self.request("POST", "v1/auth/register", {
                "registration_token": result["registration_token"], "pd_consent_accepted": True,
                "pd_consent_version": document["version"], "timezone": "Europe/Moscow",
            }, headers={"Idempotency-Key": str(uuid.uuid4())})
            self.secrets.update((registered["access_token"], registered["refresh_token"]))
            account = OwnedAccount(self.run_id, phone, registered["user_id"],
                                   registered["access_token"], registered["refresh_token"])
            account.login_ready_at = ready_at
            self.owned.append(account)
            return account
        raise StageFailure("synthetic_phone_collisions")

    def adopt_registration(self, phone, user_id):
        """Adopt only a correlated successful new registration of this run's synthetic candidate."""
        if phone not in self.unused or not PHONE.fullmatch(phone) or str(uuid.UUID(user_id)) != user_id:
            raise StageFailure("unowned_device_registration")
        if any(account.phone == phone for account in self.owned):
            raise StageFailure("duplicate_device_registration")
        self.unused.remove(phone)
        account = OwnedAccount(self.run_id, phone, user_id, "", "")
        self.owned.append(account)
        return account

    @staticmethod
    def verification_ready_at(verification):
        seconds = verification.get("expires_in")
        if type(seconds) is not int or not 0 <= seconds <= 3600:
            raise StageFailure("missing_or_invalid_verification_lifetime")
        return time.monotonic() + seconds + 1

    def refresh_owned(self, account):
        self.assert_owned(account)
        if not account.refresh:
            return
        response = self.request("POST", "v1/auth/token", {"refresh_token": account.refresh},
                                headers={"Idempotency-Key": str(uuid.uuid4())})
        self.secrets.update((response["access_token"], response["refresh_token"]))
        account.access, account.refresh = response["access_token"], response["refresh_token"]

    def assert_owned(self, account):
        if not any(account is item for item in self.owned) or account.run_id != self.run_id or not PHONE.fullmatch(account.phone):
            raise StageFailure("account_not_owned_by_run")

    def seed(self, account, content: bytes, name="fixture.ofx", timeout=120):
        self.assert_owned(account)
        if len(content) > 10 * 1024 * 1024 or not re.fullmatch(r"[a-z0-9-]+\.ofx", name):
            raise StageFailure("invalid_synthetic_fixture")
        boundary = "fin386-" + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                'Content-Type: application/x-ofx\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
        accepted = self.request("POST", "v1/statements", token=account.access, raw=data, headers={
            "Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": str(uuid.uuid4()),
        })
        upload = accepted["upload_id"]
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            uploads = self.request("GET", "v1/statements", token=account.access)
            row = next((row for row in uploads["uploads"] if row["upload_id"] == upload), None)
            if row and row.get("status") == "done":
                if row.get("operation_count", 0) > 0:
                    self.wait_analytics(account, deadline)
                return row
            time.sleep(2)
        raise StageFailure("seed_import_timeout")

    def wait_analytics(self, account, deadline):
        while time.monotonic() < deadline:
            analytics = self.request("GET", "v1/analytics?period=month", token=account.access)
            periods = self.request("GET", "v1/analytics/periods?period=month", token=account.access)
            if analytics.get("has_data") is True and periods.get("periods") and not analytics.get("state", {}).get("recalculating", False):
                return
            time.sleep(2)
        raise StageFailure("seed_analytics_timeout")

    def delete_owned(self, account):
        self.assert_owned(account)
        if account.deleted:
            return
        try:
            profile = self.request("GET", "v1/profile", token=account.access)
        except StageFailure as error:
            # A test may already have deleted its account; an expired/revoked token proves nothing.
            if str(error) not in ("stage_http_401", "stage_http_404"):
                raise
            # An invalid token alone does not prove deletion. Reconcile the owned identity through fake-phone.
            document = self.request("GET", "v1/consents/personal_data")
            verification = self.request("POST", "v1/auth/phone", {
                "phone": account.phone, "pd_consent_accepted": True,
                "pd_consent_version": document["version"], "age_confirmed": True,
            }, headers={"Idempotency-Key": str(uuid.uuid4())})
            events = self.request("GET", "v1/auth/phone/events", headers={
                "X-Verification-Token": verification["verification_token"], "Accept": "text/event-stream",
            })
            result = next((event for event in events if event.get("status") == "verified"), None)
            if result and result.get("is_new_user") is True:
                account.deleted = True
                return
            if not result or result.get("is_new_user") is not False or not result.get("access_token"):
                raise StageFailure("cleanup_identity_not_verified")
            account.access = result["access_token"]
            self.secrets.add(account.access)
            profile = self.request("GET", "v1/profile", token=account.access)
        if profile.get("user_id", profile.get("id")) != account.user_id:
            raise StageFailure("cleanup_identity_mismatch")
        self.request("DELETE", "v1/profile", token=account.access)
        account.deleted = True
