CREATE TABLE application (
    id text NOT NULL,
    application_status integer NOT NULL,
    version_status integer NOT NULL,
    build_flag integer NOT NULL,
    ean text NOT NULL,
    name text NOT NULL,
    bn_version integer NOT NULL,
    version numeric NOT NULL,
    price numeric NOT NULL
);


ALTER TABLE public.application OWNER TO nook;

CREATE TABLE sales_record (
    vendor_number text NOT NULL,
    month integer NOT NULL,
    year integer NOT NULL,
    isbn_ean text NOT NULL,
    title text,
    list_price numeric NOT NULL,
    vendor_revenue_per_unit numeric NOT NULL,
    net_unit_sold integer NOT NULL,
    total_vendor_revenue numeric NOT NULL,
    display_isbn text,
    units_sold integer NOT NULL,
    units_returned integer NOT NULL,
    date_of_sale timestamp without time zone NOT NULL
);


ALTER TABLE public.sales_record OWNER TO nook;
