const fs = require('fs');
const lines = fs.readFileSync('backend.log', 'utf8').split('\n');
for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes('Error handler threw an exception')) {
        let trace = [];
        for(let j=i; j<i+50 && j<lines.length; j++) {
            trace.push(lines[j]);
        }
        fs.writeFileSync('trace_output.txt', trace.join('\n'));
        break;
    }
}
