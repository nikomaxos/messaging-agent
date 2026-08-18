const jwt = require('jsonwebtoken');
const token = jwt.sign({ sub: 'admin', roles: ['ROLE_ADMIN'] }, 'change-me-in-production-this-must-be-at-least-32-chars');
console.log(token);
