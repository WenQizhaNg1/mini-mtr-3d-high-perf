import type { MtrScheduleResponse } from '../domain/types.js';

const MTR_SCHEDULE_URL = 'https://rt.data.gov.hk/v1/transport/mtr/getSchedule.php';

export interface MtrClient {
    getSchedule(line: string, station: string, signal?: AbortSignal): Promise<MtrScheduleResponse>;
}

export function createMtrClient(): MtrClient {
    return {
        async getSchedule(line, station, signal) {
            const url = new URL(MTR_SCHEDULE_URL);
            url.search = new URLSearchParams({
                type: 'mtr',
                line,
                sta: station,
                lang: 'EN',
            }).toString();
            const response = await fetch(url, { signal });
            if (!response.ok) {
                throw new Error(`${line}-${station} returned HTTP ${response.status}`);
            }
            return response.json() as Promise<MtrScheduleResponse>;
        },
    };
}
