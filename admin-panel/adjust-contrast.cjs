const fs = require('fs');
const path = require('path');

function processFile(filePath) {
    if (filePath.endsWith('.tsx') || filePath.endsWith('.ts')) {
        let content = fs.readFileSync(filePath, 'utf8');
        
        // Change bg-slate-50 to bg-slate-100 for better contrast against bg-white frames
        content = content.replace(/bg-slate-50 dark:bg-\[\#1a1a2e\]/g, 'bg-slate-100 dark:bg-[#1a1a2e]');
        content = content.replace(/bg-slate-50 dark:bg-\[\#0f0f1a\]/g, 'bg-slate-100 dark:bg-[#0f0f1a]');
        
        // Optional: darken borders slightly in light mode for better frame distinction
        content = content.replace(/border-slate-200 dark:border-white\/5/g, 'border-slate-300 dark:border-white/5');
        content = content.replace(/border-slate-200 dark:border-white\/10/g, 'border-slate-300 dark:border-white/10');
        
        fs.writeFileSync(filePath, content, 'utf8');
    }
}

function walkDir(dir) {
    const files = fs.readdirSync(dir);
    for (const file of files) {
        const fullPath = path.join(dir, file);
        if (fs.statSync(fullPath).isDirectory()) {
            walkDir(fullPath);
        } else {
            processFile(fullPath);
        }
    }
}

walkDir(path.join(__dirname, 'src'));
console.log('Contrast adjustment complete');
