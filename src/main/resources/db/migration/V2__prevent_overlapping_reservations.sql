CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE public.reservations
    ADD CONSTRAINT reservations_no_active_overlap
    EXCLUDE USING gist (
        car_id WITH =,
        tsrange(start_date, end_date, '[)') WITH &&
    )
    WHERE (status IN ('PENDING', 'CONFIRMED'));