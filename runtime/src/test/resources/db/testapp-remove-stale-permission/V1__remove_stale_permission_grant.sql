-- Application release removes its former code-defined capability before runtime validation.
DELETE FROM commerce.role_permissions WHERE permission_key = 'example.custom.operation';
