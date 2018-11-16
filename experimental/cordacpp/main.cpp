#include "corda.h"
#include "all-messages.h"

#include <iostream>
#include <fstream>
#include <sstream>

using namespace std;

namespace corda = net::corda;
namespace transactions = net::corda::core::transactions;

int main() {
    ifstream stream("/Users/mike/Corda/open/stx1");
    //ifstream stream("/tmp/buf");
    string bits = string((istreambuf_iterator<char>(stream)), (istreambuf_iterator<char>()));

    if (bits.empty()) {
        cerr << "Failed to read file" << endl;
        return 1;
    }

    auto stx = corda::parse<transactions::SignedTransaction>(bits);
    cout << corda::dump(stx->tx_bits->bytes) << endl;
    auto wtx = corda::parse<net::corda::core::transactions::WireTransaction>(stx->tx_bits->bytes);
    return 0;
}
