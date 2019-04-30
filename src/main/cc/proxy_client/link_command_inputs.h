#ifndef REMOTE_CLIENT_LINK_COMMAND_INPUTS_H_
#define REMOTE_CLIENT_LINK_COMMAND_INPUTS_H_

#include <set>
#include <string>

namespace remote_client {

using std::set;
using std::string;

void FindLibraryInputs(int argc, char** argv, set<string>* libPaths);

}

#endif // REMOTE_CLIENT_LINK_COMMAND_INPUTS_H_
