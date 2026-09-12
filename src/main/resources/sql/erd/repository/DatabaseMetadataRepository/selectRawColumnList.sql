SELECT
    a.attname::varchar AS name,
    pg_catalog.format_type(a.atttypid, a.atttypmod)::varchar AS type,
    NOT a.attnotnull::boolean AS nullable,
    pg_catalog.col_description(c.oid, a.attnum)::varchar AS comment
FROM
    pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n
             ON n.oid = c.relnamespace
        JOIN pg_catalog.pg_attribute a
             ON a.attrelid = c.oid
WHERE
    n.nspname = #{schema}
  AND c.relname = #{table}
    AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND a.attnum > 0
    AND NOT a.attisdropped
ORDER BY
    a.attnum
