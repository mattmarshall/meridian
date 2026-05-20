import { MeridianDeclarativeContract } from '../src/types/global.js';

const contract: MeridianDeclarativeContract = {
  initialState: { results: [] },
  transitions: {
    'search/submit': [
      {
        effect: 'fetch',
        params: { url: '/api/search', body: '${payload.query}' },
        onSuccess: [{ type: 'results/received', payload: '${result}' }],
        onError: [{ type: 'results/error', payload: '${error}' }],
      },
    ],
    'results/received': [
      { effect: 'setState', params: { results: '${payload}' } },
      { effect: 'postMessage', params: { type: 'results/ready', payload: '${payload}' } },
    ],
  },
};

export default contract;
