const fs = require('fs');
const path = require('path');

function processFile(filePath) {
    if (filePath.endsWith('.tsx') || filePath.endsWith('.ts')) {
        let content = fs.readFileSync(filePath, 'utf8');
        
        // Massive regex replacements for theme conversion
        // Ensure we don't accidentally double-prefix by checking if it's already prefixed
        // bg-[#1a1a2e] -> bg-slate-50 dark:bg-[#1a1a2e]
        content = content.replace(/(?<!dark:)bg-\[\#1a1a2e\]/g, 'bg-slate-50 dark:bg-[#1a1a2e]');
        
        // bg-[#12121f] -> bg-white dark:bg-[#12121f]
        content = content.replace(/(?<!dark:)bg-\[\#12121f\]/g, 'bg-white dark:bg-[#12121f]');
        
        // text-slate-400 -> text-slate-600 dark:text-slate-400
        content = content.replace(/(?<!dark:)text-slate-400/g, 'text-slate-600 dark:text-slate-400');
        
        // text-white -> text-slate-900 dark:text-white
        content = content.replace(/(?<!dark:)text-white/g, 'text-slate-900 dark:text-white');
        
        // border-white/5 -> border-slate-200 dark:border-white/5
        content = content.replace(/(?<!dark:)border-white\/5/g, 'border-slate-200 dark:border-white/5');
        
        // border-white/10 -> border-slate-200 dark:border-white/10
        content = content.replace(/(?<!dark:)border-white\/10/g, 'border-slate-200 dark:border-white/10');
        
        // border-white/20 -> border-slate-300 dark:border-white/20
        content = content.replace(/(?<!dark:)border-white\/20/g, 'border-slate-300 dark:border-white/20');
        
        // bg-white/5 -> bg-slate-200/50 dark:bg-white/5
        content = content.replace(/(?<!dark:)bg-white\/5/g, 'bg-slate-200/50 dark:bg-white/5');
        
        // bg-white/10 -> bg-slate-200 dark:bg-white/10
        content = content.replace(/(?<!dark:)bg-white\/10/g, 'bg-slate-200 dark:bg-white/10');

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
console.log('Migration complete');
