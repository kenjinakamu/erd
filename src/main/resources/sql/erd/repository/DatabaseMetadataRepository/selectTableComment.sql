SELECT
    pg_catalog.obj_description(cls.oid, 'pg_class')
FROM
    pg_catalog.pg_class cls
        JOIN pg_catalog.pg_namespace ns
             ON ns.oid = cls.relnamespace
WHERE
    ns.nspname = #{schema}
  AND cls.relname = #{table}
    AND cls.relkind IN ('r', 'p', 'v', 'm', 'f')
