ALTER TABLE users
    ALTER COLUMN theme SET DEFAULT 'booklib';

UPDATE users
SET theme = 'booklib'
WHERE theme = 'grimmory';
