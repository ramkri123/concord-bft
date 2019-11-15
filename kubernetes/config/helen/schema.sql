-- This script creates a new cockroach database named 'helen' and also
-- creates a new admin user named 'helen_admin' for that database. As of
-- now contracts management services and profile(user) management services
-- need this database.
-- The table creation part for profile(user) managment services is done
-- in this script (because those services are developed using hibernate and
-- so we don't have to directly deal with SQL quries). However, the
-- contract management services were developed earlier and hence they
-- directly deal with SQL queries using JDBC. Hence, contract management
-- table creation is not done here

-- Create a helen database
CREATE DATABASE IF NOT EXISTS helen;

-- Allow helen_admin all access to helen database
GRANT ALL ON DATABASE helen TO helen_admin;

-- switch to helen database
use helen;


-- Profile schema creation
-- Below queries are taken from hibernate logs and will have to be modified
-- if we add a new persistent entity or update existing entity.

-- Sequence used by hibernate for assigning auto generated ID
create sequence if not exists hibernate_sequence start 1 increment 1;

-- Consoritums entity
create table if not exists consortiums (consortiumid UUID not null,
consortium_name varchar(255),
consortium_type varchar(255), primary key (consortiumid));

-- Organizations entity
create table if not exists organizations (organizationid UUID not null,
organization_name varchar(255),
primary key (organizationid));

-- Users entity
create table if not exists users (userid UUID not null, email
varchar(255) UNIQUE, first_name varchar(255), last_login int8, last_name
varchar(255), name varchar(255) not null, password varchar(255) not
null, role varchar(255) not null, consortium_consortiumid UUID not
null, organization_organizationid UUID not null, primary key (userid),
foreign key (consortium_consortiumid) references consortiums, foreign
key (organization_organizationid) references organizations);

-- keystore entity
create table if not exists keystores (address varchar(40) not null,
wallet text not null, user_userid UUID, foreign key (user_userid) references users,
primary key (address));

-- contracts --
create sequence if not exists contract_sequence start with 1 increment 1;

create table if not exists contracts (id UUID not null, contract_id text not null, version_name text not null,
address text, sourcecode text, bytecode text, metadata text, owner text,
sequence_number integer default nextval('contract_sequence'),
blockchain_id UUID not null,
primary key (id));

-- entity tables --
create table if not exists entity (
  created_id   bigserial,
  row_key      uuid   not null,
  column_name  varchar(64)  not null,
  version      int          not null,
  body         jsonb         not null, -- 64kb; native json in 10.2?
  user_id      uuid   not null,
  user_name    varchar(64)  not null,
  created_tms  timestamp    not null  default current_timestamp,
  primary key (created_id),
  unique (row_key, version)
);

create table if not exists entity_history (
  created_id   bigserial,
  row_key      uuid   not null,
  column_name  varchar(64)  not null,
  version      int          not null,
  body         jsonb         not null, -- 64kb; native json in 10.2?
  user_id      uuid   not null,
  user_name    varchar(64)  not null,
  created_tms  timestamp    not null  default current_timestamp,
  primary key (created_id),
  unique (row_key, version)
);

-- relationships should be kept in entities' bodies. link table is a performance optimization for queries
create table if not exists link (
  from_row  uuid  not null,
  to_row    uuid  not null,
  unique (from_row, to_row)
  );


