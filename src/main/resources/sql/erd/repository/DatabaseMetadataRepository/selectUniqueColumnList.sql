SELECT
    a.attname AS column_name,
    cardinality(con.conkey) AS column_count
FROM
    pg_catalog.pg_constraint con
        JOIN pg_catalog.pg_class c
             ON c.oid = con.conrelid
        JOIN pg_catalog.pg_namespace n
             ON n.oid = c.relnamespace
        JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS key(attnum, ord)
             ON TRUE
        JOIN pg_catalog.pg_attribute a
             ON a.attrelid = c.oid
                 AND a.attnum = key.attnum
WHERE
    con.contype = 'u'
  AND n.nspname = #{schema}
  AND c.relname = #{table}
ORDER BY
    con.conname,
    key.ord
