# car-dealer

Deployment guide: [GitHub, GHCR, Argo CD and k3s](docs/deployment.md).

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_** Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

# Car Rental API

Java 21+, Quarkus, Hibernate ORM with Panache, and PostgreSQL.

## Local development

Start the local PostgreSQL container. An empty database is initialized by Flyway;
restoring an existing schema is optional.
The `%dev` datasource in `src/main/resources/application.properties` uses
`localhost:5433/cardealer`. Production uses `DB_JDBC_URL`, `DB_USERNAME`, and
`DB_PASSWORD` environment variables, not a hardcoded VPS address or credentials.

```powershell
docker start carrental-postgres
.\mvnw.cmd quarkus:dev
```

The API is available at `http://localhost:8080/api`.
Restart dev mode after adding the authentication dependencies.
In dev mode, Swagger UI is available at `http://localhost:8080/q/swagger-ui/`
and the OpenAPI document at `http://localhost:8080/q/openapi`.

Flyway applies V0 (initial rental tables), V1 (accounts), and V2 (overlap protection)
to empty databases. A restored existing schema is baselined at version 0, skipping
V0 and applying V1/V2 without replacing existing rental tables or records. Hibernate remains
in `validate` mode; do not switch it to `drop-and-create` on your own database.

## Roles

| Action                                          | Anonymous | CUSTOMER | EMPLOYEE | ADMIN |
| ----------------------------------------------- | --------- | -------- | -------- | ----- |
| Browse cars, brands, locations                  | Yes       | Yes      | Yes      | Yes   |
| Register and log in                             | Yes       | Yes      | Yes      | Yes   |
| Read own account                                | No        | Yes      | Yes      | Yes   |
| Read/update own customer profile                | No        | Yes      | Yes      | Yes   |
| List all customers                              | No        | No       | Yes      | Yes   |
| Create/delete customer records                  | No        | No       | Yes      | Yes   |
| Create/update/delete cars, brands, locations    | No        | No       | Yes      | Yes   |
| Create/read/update own reservations             | No        | Yes      | Yes      | Yes   |
| Manage any customer's reservations              | No        | No       | Yes      | Yes   |
| Cancel own pending/confirmed reservation        | No        | Yes      | Yes      | Yes   |
| Permanently delete reservations                 | No        | No       | Yes      | Yes   |
| Create accounts, change roles, disable accounts | No        | No       | No       | Yes   |

Unannotated REST endpoints are denied by default. Customers cannot change
ownership, price, or status through a reservation request. All reservation prices,
including employee/admin bookings, are calculated from the car's daily price
times the number of started 24-hour periods. A submitted `totalPrice` is ignored;
it never overrides the calculated amount. New reservations always start in
`PENDING`; requesting another initial status returns 400. Customers can edit only
pending reservations and cancel pending/confirmed reservations. Active overlaps
are rejected on creation and update for every role.

## Reservation workflow and pricing

Allowed status transitions:

| Current status | Allowed next statuses |
| -------------- | --------------------- |
| PENDING        | CONFIRMED, CANCELLED  |
| CONFIRMED      | COMPLETED, CANCELLED  |
| CANCELLED      | None                  |
| COMPLETED      | None                  |

Employees/admins can change status with
`PATCH /api/reservations/{id}/status`, using their Bearer token and JSON body:

```json
{
  "status": "CONFIRMED"
}
```

A same-status request on an active reservation is a no-op. An invalid transition
returns 409. Completion is allowed only after the reservation's end time.
Customers cannot call this endpoint; they use the bodyless
`POST /api/reservations/{id}/cancel` endpoint instead. Staff may also submit a
status with a full `PUT`, subject to exactly the same transition rules. Omitting
status on an update preserves it. Cancelled/completed bookings cannot be edited
or reactivated, even by staff.

Full booking updates recalculate the total on the server. A status-only PATCH
preserves the agreed total. Prices must fit the existing `numeric(10,2)` column;
overlarge totals return 400 instead of an SQL error. Cars whose stored daily
price is zero/negative cannot be booked. `MAINTENANCE`, `INACTIVE`, and `RENTED`
cars cannot receive active bookings; `AVAILABLE`/`RESERVED` cars still undergo
date-overlap checks. A future booking does not globally change the car's status.

## Request validation

All CRUD POST/PUT bodies are validated before database writes. Customer, brand,
and location writes use request DTOs rather than persistence entities, so client
IDs and creation timestamps are not accepted as writable entity fields.

- Required text fields must not be blank and must fit their database column lengths.
- Emails must have a valid format; customer/account emails are normalized to lowercase.
- IDs must be positive, including path parameters.
- Car daily prices must be positive, at most `99999999.99`, with at most two decimals.
- Mileage must be nonnegative and cannot decrease on update (409); seat count must be an integer from 1 to 9.
- Production years must be at least 1886 and no later than next year.
- Car/reservation status values must belong to the supported sets.
- Birth dates must be in the past, if provided.
- Reservation dates are required, and end time must be after start time.
- New reservations cannot start in the past (400), for customers and staff alike.
  Existing past reservations remain administrable under the normal role/status rules.
- JSON fractional numbers cannot be silently truncated into integer fields.

On car creation, omitted mileage defaults to 0 and omitted status defaults to
`AVAILABLE`. On car update, omitting either field preserves its stored value;
an explicit null remains invalid. Other required PUT fields must still be supplied.
Car updates lock the database row before comparing mileage so concurrent updates
cannot overwrite a higher reading with a lower one.

## Error responses

REST errors use a consistent JSON shape. Validation responses identify fields
without echoing submitted values, passwords, SQL statements, or stack traces:

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "errors": {
    "dailyPrice": "must be greater than 0"
  }
}
```

Non-validation errors keep `errors` as an empty object. Typical codes:

| HTTP | Code                   | Meaning                                                       |
| ---- | ---------------------- | ------------------------------------------------------------- |
| 400  | VALIDATION_ERROR       | Missing/invalid request fields                                |
| 400  | BAD_REQUEST            | Malformed JSON or invalid business input                      |
| 401  | UNAUTHORIZED           | Missing, invalid, expired, or revoked token                   |
| 403  | FORBIDDEN              | Insufficient permissions                                      |
| 404  | NOT_FOUND              | Missing record or another customer's private record           |
| 405  | METHOD_NOT_ALLOWED     | Wrong HTTP method                                             |
| 409  | CONFLICT               | Invalid state transition or unavailable car                   |
| 409  | DUPLICATE_RESOURCE     | Duplicate unique plate, VIN, email, license, or brand         |
| 409  | REFERENCED_RESOURCE    | Conflict with a related record, such as deleting a used brand |
| 409  | RESERVATION_CONFLICT   | PostgreSQL-detected overlapping active reservations           |
| 415  | UNSUPPORTED_MEDIA_TYPE | Wrong request Content-Type                                    |
| 500  | INTERNAL_ERROR         | Unexpected failure, details logged server-side only           |

Duplicate/foreign-key errors are flushed inside transactions and returned safely
as 409; failed writes are rolled back. Application-level duplicate registration
and overlap checks may return the general `CONFLICT` code. Use HTTP status and
the code rather than parsing the human-readable message.

## Reservation overlap protection

`PENDING` and `CONFIRMED` reservations block a car for the half-open interval
`[startDate, endDate)`. A booking may start exactly when another one ends.
`CANCELLED` and `COMPLETED` reservations do not block availability. The same
interval can be booked for different cars. Updating a booking excludes that
booking itself from the overlap check; a conflicting update leaves it unchanged.
Cancelled/completed bookings never block availability, but cannot be reactivated
through the API. An omitted status on a staff update preserves the existing status.

The service locks the target car row for the transaction before checking overlaps.
Updates and cancellations also lock the reservation row. PostgreSQL adds the
`reservations_no_active_overlap` GiST exclusion constraint through migration V2,
so writes outside the API cannot bypass the rule. Conflicting API bookings return
HTTP 409 with `Car is already reserved for the requested period`.

V2 installs the PostgreSQL `btree_gist` extension and requires permission to
install it and alter `public.reservations`. On a restricted production account,
have a database administrator install the extension and run the migration.
The exclusion constraint also creates its supporting GiST index automatically.

Before enabling V2 on an existing database, inspect active overlaps:

```sql
SELECT first_booking.id AS first_id, second_booking.id AS second_id,
			 first_booking.car_id
FROM public.reservations first_booking
JOIN public.reservations second_booking
	ON first_booking.id < second_booking.id
 AND first_booking.car_id = second_booking.car_id
 AND first_booking.start_date < second_booking.end_date
 AND first_booking.end_date > second_booking.start_date
WHERE first_booking.status IN ('PENDING', 'CONFIRMED')
	AND second_booking.status IN ('PENDING', 'CONFIRMED');
```

If overlaps already exist, V2 fails without deleting or cancelling records.
Review and resolve those records deliberately, then restart to retry the migration.

Public car responses omit VIN and license plate. Reservation responses contain
customer IDs, not embedded personal details. Account responses never contain
passwords or password hashes. Customer contact email and account login email are
separate: changing the profile does not change login credentials.

## Create the first administrator

There is no default admin password and no public admin registration endpoint.
Stop the existing backend, then run these commands in the project directory.
Enter your chosen credentials directly in the terminal; the password prompt is
masked. Passwords require at least 12 characters and at most 72 UTF-8 bytes.

```powershell
$env:APP_AUTH_BOOTSTRAP_ADMIN_EMAIL = Read-Host "Admin email"
$adminPassword = Read-Host "Admin password" -AsSecureString
$env:APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new("", $adminPassword).Password
try {
		.\mvnw.cmd quarkus:dev
} finally {
		Remove-Item Env:APP_AUTH_BOOTSTRAP_ADMIN_EMAIL -ErrorAction SilentlyContinue
		Remove-Item Env:APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
		$adminPassword.Dispose()
		Remove-Variable adminPassword
}
```

The account is created only when no enabled administrator exists. These settings
do not reset an existing admin password. Remove the bootstrap credentials after
initialization; the account is stored in PostgreSQL and ordinary future starts
only need `quarkus:dev`. Administrators cannot change their own access.

## Registration and login

`POST /api/auth/register` always creates a `CUSTOMER` account and a new customer
profile. Submitted role, enabled state, or customer ID cannot grant privileges.

```json
{
  "email": "customer@example.com",
  "password": "choose-a-long-password",
  "firstName": "Test",
  "lastName": "Customer",
  "drivingLicenseNumber": "LICENSE-EXAMPLE-001"
}
```

Phone, date of birth, license expiry, and address are optional. If the customer
already exists, registration returns 409 instead of letting an unauthenticated
person claim that profile. An admin must create and link the account.

`POST /api/auth/login` accepts `email` and `password`, and returns `accessToken`,
`tokenType`, `expiresIn` (900 seconds by default), and a safe account view.
Use the token on protected requests:

```http
Authorization: Bearer <accessToken>
```

`GET /api/auth/me` returns the current account; `GET /api/customers/me` returns
the current customer's profile. Customers may also use `GET/PUT /api/customers/{id}`
for their own ID. Accessing another customer's profile or reservation returns 404.
`GET /api/reservations` returns only the caller's reservations for customers,
and all reservations for staff. Customers can omit `customerId` when creating a
reservation; an explicitly foreign ID is rejected. Cancellation uses
`POST /api/reservations/{id}/cancel` without a request body.

Passwords use bcrypt with cost 12. Issuer, audience, signature, expiration,
account status, current role, and token version are checked. Disabling an account
or updating its role invalidates its previously issued tokens immediately.
There are no refresh tokens yet: log in again after expiration. Dev/test RSA
keys are generated by Quarkus and may change after a restart.

## Account administration

All `/api/users` endpoints require `ADMIN`:

- `GET /api/users`: list safe account views.
- `GET /api/users/{id}`: inspect an account.
- `POST /api/users`: create an account with `email`, `password`, and `role`.
- `PUT /api/users/{id}/access`: set `role` and `enabled`.

For `CUSTOMER` account creation, include an existing, unlinked `customerId`.
Employee/admin accounts do not require a customer profile. One customer can
belong to only one account. Changing an account to `CUSTOMER` also requires a
linked profile. Account creation is admin-only, even for an existing customer.

Example access update:

```json
{
  "role": "EMPLOYEE",
  "enabled": true
}
```

## Tests

Docker must be running. Execute the complete suite with:

```powershell
.\mvnw.cmd clean test
```

Tests start disposable PostgreSQL 17 containers on random ports. They never use
the VPS or local development database. The suite covers registration, bcrypt,
login failures, roles, ownership, account linking, token expiry and revocation,
admin bootstrap, and repeatable additive database migration. Booking tests cover
overlap shapes, adjacent intervals, different cars, conflicting updates, terminal
states, concurrent HTTP creation/updates, and concurrent direct SQL writes.
Additional tests cover day-rounding and price tampering, invalid car/profile
fields, integer coercion, duplicate creation/update rollback, foreign-key conflicts,
HTTP error formats, and safe field-level validation messages. No template
`/hello` test remains.

## Production deployment

Development auto-generated signing keys are not used in production. Generate an
RSA key pair outside the repository and mount it using a Kubernetes Secret:

```shell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out privateKey.pem
openssl pkey -in privateKey.pem -pubout -out publicKey.pem
```

Set `JWT_PRIVATE_KEY_LOCATION` and `JWT_PUBLIC_KEY_LOCATION` to the mounted files
(for example `file:/run/secrets/jwt/privateKey.pem` and the matching public key).
Do not commit private keys. Set `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD`
from protected runtime configuration. See the deployment guide for separate
migration/runtime credentials, initial admin setup and Kubernetes manifests.

Production migration is deliberately off by default. After backing up and
verifying the target database, run the separate `--migrate` mode / Kubernetes Job
described in the deployment guide before serving requests. Baseline an existing
schema at version 0 only after reviewing it; keep migration flags off on normal
serving pods. Never use Flyway clean on a database containing real data.

Before public deployment, also configure HTTPS, login/registration rate limiting,
and private/restricted PostgreSQL access. The current authentication implementation
does not provide email verification, password recovery, or login throttling.

In production, the OpenAPI document and Swagger UI are available at
`https://<api-host>/q/openapi` and `https://<api-host>/q/swagger-ui/` when the
HTTPS Ingress overlay is enabled. Swagger UI includes the Bearer JWT authorization
scheme; log in at `/api/auth/login`, copy `accessToken`, choose **Authorize**, and
enter the token. Do not paste credentials or tokens into public issue trackers.
The `/q` route is intentionally limited to the OpenAPI document and Swagger UI;
Kubernetes health endpoints are not exposed by the Ingress.

Build with `.\mvnw.cmd package`, then run
`java -jar target/quarkus-app/quarkus-run.jar` with production keys configured.
