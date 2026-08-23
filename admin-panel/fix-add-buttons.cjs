const fs = require('fs');
const path = require('path');

function processFile(filePath) {
    if (!filePath.endsWith('.tsx')) return;
    
    let content = fs.readFileSync(filePath, 'utf8');
    let original = content;

    // 1. In AccountsPage.tsx, "Add Username" button
    content = content.replace(
        /className="bg-brand-600\/20 hover:bg-brand-600\/30 text-brand-400 border border-brand-500\/30 px-3 py-1.5 rounded-lg text-xs font-medium transition flex items-center gap-1"/g,
        'className="bg-brand-600 hover:bg-brand-500 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition flex items-center gap-1"'
    );

    // 2. In SmppRoutingPage.tsx, "Add Target Destination" button
    content = content.replace(
        /className="text-sm text-brand-400 hover:text-brand-300 font-medium flex items-center gap-1 transition mt-2"/g,
        'className="bg-brand-600 hover:bg-brand-500 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition flex items-center gap-1 w-fit mt-2"'
    );

    // 3. In RoutingRulesPage.tsx, "Add Trigger" and "Add Action" buttons
    content = content.replace(
        /className="mt-4 flex items-center gap-2 text-sm text-brand-400 hover:text-brand-300 font-medium transition"/g,
        'className="mt-4 bg-brand-600 hover:bg-brand-500 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition flex items-center gap-1 w-fit"'
    );

    // Also look for any generic "text-brand-400 hover:text-brand-300" that are Add buttons
    // e.g. DevicesPage.tsx Add Device? Let's check DevicesPage.tsx.
    
    // We'll also just globally replace bg-brand-600/20 if it's used for Add buttons.
    // In DevicesPage.tsx, we have "Add Device".
    
    if (content !== original) {
        fs.writeFileSync(filePath, content, 'utf8');
        console.log(`Updated ${path.basename(filePath)}`);
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

walkDir(path.join(__dirname, 'src/pages'));
