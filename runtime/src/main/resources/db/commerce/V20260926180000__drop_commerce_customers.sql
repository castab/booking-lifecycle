-- commerce.customers was an incorrectly owned runtime table. Customers, and their
-- relationships to bookings and financial documents, belong to concrete applications, and
-- commerce-runtime no longer owns a customer model.
--
-- V20260926120000 created the table. Flyway history is immutable, so that migration stays
-- and this forward migration removes the table. A fresh installation therefore creates and
-- then drops it; the commerce schema and its history remain. There is no data-preservation
-- path, archive, or compatibility layer.
DROP TABLE commerce.customers;
