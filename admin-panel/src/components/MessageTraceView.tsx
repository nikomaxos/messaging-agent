import React, { useMemo } from 'react';
import { ReactFlow, Background, Controls, Node, Edge, MarkerType } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { MessageLog } from '../types';

interface MessageTraceViewProps {
  log: MessageLog;
}

export default function MessageTraceView({ log }: MessageTraceViewProps) {
  
  const { nodes, edges } = useMemo(() => {
    const nds: Node[] = [];
    const eds: Edge[] = [];
    let yPos = 50;

    const addNode = (id: string, label: string, color: string, textColor: string = 'white') => {
      nds.push({
        id,
        data: { label },
        position: { x: 250, y: yPos },
        style: { background: color, color: textColor, borderRadius: '8px', padding: '10px', width: 250, textAlign: 'center', fontWeight: 'bold' }
      });
      yPos += 100;
    };

    const addEdge = (source: string, target: string, label?: string) => {
      eds.push({
        id: `e-${source}-${target}`,
        source,
        target,
        label,
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed, width: 20, height: 20 }
      });
    };

    // 1. Inbound Entry
    addNode('start', 'Platform Received', '#3b82f6'); // blue
    let lastId = 'start';

    // 2. Rules Engine
    if (log.traceData) {
      addNode('rules', 'Rules Engine', '#f59e0b'); // amber
      addEdge(lastId, 'rules');
      lastId = 'rules';
      
      const traces = log.traceData.split(' | ').filter((t: string) => t.trim() !== '');
      traces.forEach((trace: string, idx: number) => {
        const traceId = `trace-${idx}`;
        let color = '#475569'; // slate
        
        if (trace.includes('Terminated')) color = '#ef4444'; // red
        else if (trace.includes('Rewrote') || trace.includes('Override')) color = '#10b981'; // emerald
        
        addNode(traceId, trace, color);
        addEdge(lastId, traceId);
        lastId = traceId;
      });
    }

    // 3. Routing (If not terminated by rules)
    if (!log.traceData?.includes('Terminated')) {
      if (log.routingMode) {
        addNode('routing', `Routing: ${log.routingMode}`, '#8b5cf6'); // violet
        addEdge(lastId, 'routing');
        lastId = 'routing';

        if (log.routingMode === 'WEBSOCKET' && (log.device || log.deviceGroup)) {
           addNode('device', `Target: ${log.device?.name || log.deviceGroup?.name}`, '#06b6d4'); // cyan
           addEdge(lastId, 'device');
           lastId = 'device';
        }
      }
    }

    // 4. Fallback (If fallback occurred)
    if (log.fallbackStartedAt && log.fallbackSmsc) {
      addNode('fallback', 'Fallback Triggered', '#f97316'); // orange
      addEdge(lastId, 'fallback', log.errorDetail?.substring(0,20) || 'Timeout/Error');
      lastId = 'fallback';

      addNode('fallback-dest', `Fallback SMSC: ${log.fallbackSmsc.name}`, '#f59e0b');
      addEdge(lastId, 'fallback-dest');
      lastId = 'fallback-dest';
    }

    // 5. Final Status
    let statusColor = '#64748b'; // default slate
    if (log.status === 'DELIVERED') statusColor = '#22c55e'; // green
    else if (log.status === 'FAILED' || log.status === 'RCS_FAILED') statusColor = '#ef4444'; // red
    else if (log.status === 'QUEUED') statusColor = '#a855f7'; // purple
    else if (log.status === 'DISPATCHED') statusColor = '#eab308'; // yellow
    else if (log.isEmulated) statusColor = '#6366f1'; // indigo

    addNode('status', `Final Status: ${log.isEmulated ? 'EMULATED (FAKE)' : log.status}`, statusColor);
    addEdge(lastId, 'status');

    return { nodes: nds, edges: eds };
  }, [log]);

  return (
    <div className="w-full h-[400px] border border-slate-700 rounded-lg overflow-hidden bg-white dark:bg-slate-900">
      <ReactFlow nodes={nodes} edges={edges} fitView>
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}
