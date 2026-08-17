import ast
import traceback

def get_metadata(file_path):
    metadata = {}
    allowed_keys = {'__version__', '__min_version__', '__id__', '__icon__', '__name__', '__description__', '__author__', '__requirements__'}
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            source = f.read()
        tree = ast.parse(source, filename=file_path)
        for node in ast.iter_child_nodes(tree):
            if isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name) and target.id in allowed_keys:
                        try:
                            value = ast.literal_eval(node.value)
                        except (ValueError, TypeError):
                            continue
                        if target.id == '__requirements__' and isinstance(value, (list, tuple)):
                            value = [str(item).strip() for item in value if str(item).strip()]
                        metadata[target.id[2:-2]] = value
    except Exception as e:
        raise Exception(f'Failed to parse metadata: {e}\n{traceback.format_exc()}')
    return metadata
